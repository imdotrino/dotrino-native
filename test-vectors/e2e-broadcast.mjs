// Arnés de punta a punta de la EMISIÓN nativa (`BroadcastHost`): levanta un PROXIO de verdad
// (repo hermano), le da al «teléfono» su perfil (llaves en software: la prueba Kotlin firma y
// descifra con ellas) y, cuando el emisor nativo avisa de su enlace, lo MIRA un espectador que
// corre `@dotrino/lobby` tal cual (el de la PWA), con una identidad del pilar.
//
//   node test-vectors/e2e-broadcast.mjs <salida.json>
//   DOTRINO_E2E_BCAST=<salida.json> ./gradlew :dotrino-native:testDebugUnitTest --tests '*BroadcastE2eTest*'
//
// Protocolo con la prueba (archivos junto a <salida.json>):
//   <salida>          lo escribe esto: proxyUrl y las privadas del perfil y del transporte
//   <salida>.ref      lo escribe la prueba: el enlace (`key.secret.x.y`)
//   <salida>.seen     lo escribe esto: cada estado que el espectador VERIFICÓ, uno por línea
//   <salida>.denied   lo escribe esto: lo que contestó el emisor a un secreto falso
// Se cierra solo a los 2 minutos.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-broadcast.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))
setTimeout(() => { console.error('e2e-broadcast: timeout'); process.exit(3) }, 120_000).unref()

process.env.NODE_ENV = 'test'
process.env.PROXY_DB_FILE = ':memory:'
const proxy = require(path.join(root, 'dotrino-proxy/server.js'))
const port = await proxy.start(0)
const proxyUrl = `ws://${process.env.E2E_HOST || '127.0.0.1'}:${port}`

// El perfil del «teléfono» (firma + cifrado) y la llave de su transporte (firma sus canales).
const gen = async (algo, uses) => crypto.subtle.exportKey('jwk', (await crypto.subtle.generateKey(algo, true, uses)).privateKey)
const ECDSA = { name: 'ECDSA', namedCurve: 'P-256' }
const ECDH = { name: 'ECDH', namedCurve: 'P-256' }
fs.writeFileSync(out, JSON.stringify({
  proxyUrl,
  sign: await gen(ECDSA, ['sign', 'verify']),
  enc: await gen(ECDH, ['deriveBits']),
  transport: await gen(ECDSA, ['sign', 'verify']),
  transportEnc: await gen(ECDH, ['deriveBits']),
}))
console.log('e2e-broadcast: ready at', proxyUrl)

// Para la prueba de iOS, que corre en otra máquina: lo mismo por HTTP (E2E_HTTP=puerto).
//   GET /config · POST /ref · GET /seen · GET /denied · POST /done
if (process.env.E2E_HTTP) {
  const http = await import('node:http')
  http.createServer((req, res) => {
    const file = { '/config': out, '/seen': out + '.seen', '/denied': out + '.denied' }[req.url]
    if (req.method === 'GET' && file) {
      if (!fs.existsSync(file)) { res.writeHead(404); return res.end() }
      res.writeHead(200, { 'content-type': 'application/json' }); return res.end(fs.readFileSync(file))
    }
    if (req.method === 'POST' && (req.url === '/ref' || req.url === '/done')) {
      let body = ''
      req.on('data', (c) => { body += c })
      req.on('end', () => {
        if (req.url === '/ref') fs.writeFileSync(out + '.ref', body)
        else fs.unlinkSync(out)
        res.writeHead(200); res.end()
      })
      return
    }
    res.writeHead(404); res.end()
  }).listen(Number(process.env.E2E_HTTP), '0.0.0.0')
  console.log('e2e-broadcast: http on', process.env.E2E_HTTP)
}

// Espera el enlace del emisor nativo.
const refFile = out + '.ref'
while (!fs.existsSync(refFile)) await new Promise((r) => setTimeout(r, 200))
const encoded = fs.readFileSync(refFile, 'utf8').trim()

// El espectador: la PWA de verdad (lobby + proxy-client + identity de los repos hermanos).
const { Identity } = await import(path.join(root, 'dotrino-identity/src/node.js'))
const { WebSocketProxyClient } = await import(path.join(root, 'dotrino-proxy-client/src/index.js'))
const { createLobby, decodeBroadcastRef } = await import(path.join(root, 'dotrino-lobby/src/index.js'))

async function viewer (ref) {
  const identity = await Identity.connect({ dir: tmp('e2e-viewer-') })
  const client = new WebSocketProxyClient({ url: proxyUrl, enableWebRTC: false })
  const lobby = await createLobby({ gameId: 'padel', identity, proxy: client, url: proxyUrl })
  return lobby.watchBroadcast(ref)
}

const good = await viewer(decodeBroadcastRef(encoded))
good.on('state', (state, meta) => fs.appendFileSync(out + '.seen', JSON.stringify({ state, meta }) + '\n'))
good.on('status', (s) => console.log('viewer status:', JSON.stringify(s)))

// Otro con el secreto cambiado: el emisor tiene que decir que no.
const bad = decodeBroadcastRef(encoded)
bad.secret = 'not-the-secret'
const intruder = await viewer(bad)
intruder.on('status', (s) => { if (s.status === 'denied') fs.writeFileSync(out + '.denied', JSON.stringify(s)) })

// Hasta que la prueba termine (borra <salida>) o salte el tiempo.
while (fs.existsSync(out)) await new Promise((r) => setTimeout(r, 300))
await good.close(); await intruder.close()
process.exit(0)
