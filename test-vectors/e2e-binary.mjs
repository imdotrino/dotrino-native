// Arnés de punta a punta del BINARIO: un proxio de verdad (repo hermano) y un cliente del pilar
// (`@dotrino/proxy-client` + `@dotrino/identity`, los de la PWA) que le manda al «teléfono»
// archivos sellados en binario, por token y por pubkey. El teléfono contesta en texto y eso
// también se comprueba.
//
//   node test-vectors/e2e-binary.mjs <salida.json>
//   DOTRINO_E2E_BIN=<salida.json> ./gradlew :dotrino-native:testDebugUnitTest --tests '*BinaryE2eTest*'
//
// Protocolo con la prueba (archivos junto a <salida.json>):
//   <salida>        lo escribe esto: proxyUrl, las privadas del perfil del teléfono, y el token y
//                   la pubkey del cliente JS
//   <salida>.sent   lo escribe esto cuando mandó los dos archivos (con su base64, para comparar)
//   <salida>.reply  lo escribe esto con lo que el teléfono le contestó
// Se cierra solo a los 2 minutos, o cuando la prueba borra <salida>.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-binary.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))
setTimeout(() => { console.error('e2e-binary: timeout'); process.exit(3) }, 120_000).unref()

process.env.NODE_ENV = 'test'
process.env.PROXY_DB_FILE = ':memory:'
const proxy = require(path.join(root, 'dotrino-proxy/server.js'))
const port = await proxy.start(0)
const proxyUrl = `ws://${process.env.E2E_HOST || '127.0.0.1'}:${port}`

const { Identity } = await import(path.join(root, 'dotrino-identity/src/node.js'))
const { WebSocketProxyClient } = await import(path.join(root, 'dotrino-proxy-client/src/index.js'))
const { identitySealing, setSealingPrimitives } = await import(path.join(root, 'dotrino-proxy-client/src/sealing.js'))
setSealingPrimitives(await import(path.join(root, 'dotrino-identity/vault/content.js')))

const identity = await Identity.connect({ dir: tmp('e2e-bin-') })
const publickey = identity.me.publickey
const sign = (d) => identity.signData(d)
const client = new WebSocketProxyClient({
  url: proxyUrl, enableWebRTC: false, requireSealed: true, app: 'messenger',
  sealing: identitySealing(identity, { app: 'messenger' }),
})
await client.connect()
await client.identifyAs({ publickey, sign })
await client.announceEncPub({ publickey, encPub: await identity.getEncryptionPubkey(), sign })

const gen = async (algo, uses) => crypto.subtle.exportKey('jwk', (await crypto.subtle.generateKey(algo, true, uses)).privateKey)
fs.writeFileSync(out, JSON.stringify({
  proxyUrl, jsToken: client.token, jsPubkey: publickey,
  sign: await gen({ name: 'ECDSA', namedCurve: 'P-256' }, ['sign', 'verify']),
  enc: await gen({ name: 'ECDH', namedCurve: 'P-256' }, ['deriveBits']),
}))
console.log('e2e-binary: ready at', proxyUrl)

// Un archivo de 200 KB con todos los valores de byte.
const file = new Uint8Array(200 * 1024)
for (let i = 0; i < file.length; i++) file[i] = (i * 7) & 0xff
const b64 = (u8) => Buffer.from(u8).toString('base64')

client.on('message', (from, msg, meta) => {
  fs.writeFileSync(out + '.reply', JSON.stringify({ msg, sealed: meta.sealed }))
})
client.on('error', (e) => console.error('e2e-binary: client error', e.type, e.code || e.reason || ''))

// Cuando el teléfono salude: tiene que decir que entiende binario, y entonces se le manda.
client.on('peer_identity', async (token, phonePub) => {
  try {
    if (client.speaks(token) !== true) { fs.writeFileSync(out + '.sent', JSON.stringify({ error: 'phone did not say binary' })); return }
    await client.sendSealedTo(token, { type: 'DM', file: { name: 'foto.bin', mime: 'application/octet-stream', data: file } }, { peerPubkey: phonePub })
    await client.sendSealed([phonePub], { type: 'QUEUED', blob: file.subarray(0, 1000) }, { app: 'messenger' })
    fs.writeFileSync(out + '.sent', JSON.stringify({ file: b64(file), blob: b64(file.subarray(0, 1000)) }))
  } catch (e) {
    fs.writeFileSync(out + '.sent', JSON.stringify({ error: `${e.code || ''} ${e.message}` }))
  }
})

while (fs.existsSync(out)) await new Promise((r) => setTimeout(r, 300))
client.close()
process.exit(0)
