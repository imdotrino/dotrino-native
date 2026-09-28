// Arnés de punta a punta de LA POLÍTICA DEL ACTA en el perfil nativo: levanta un PROXIO y una
// BÓVEDA de verdad (repos hermanos, desechables: su propio perfil y sin aprobador), empareja el
// «teléfono» y saca DOS actas reales de la bóveda — una que le da `sign` y otra que no. La prueba
// Kotlin (`ActaE2eTest`) carga el perfil con cada una: con `sign` emite y un espectador de
// `@dotrino/lobby` lo verifica; sin `sign` no firma.
//
//   node test-vectors/e2e-acta.mjs <salida.json>
//   DOTRINO_E2E_ACTA=<salida.json> ./gradlew :dotrino-native:testDebugUnitTest --tests '*ActaE2eTest*'
//
// <salida>.ref (lo escribe la prueba) · <salida>.seen (lo escribe esto). Se cierra solo a los 3 min.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-acta.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))
setTimeout(() => { console.error('e2e-acta: timeout'); process.exit(3) }, 180_000).unref()

process.env.NODE_ENV = 'test'
process.env.PROXY_DB_FILE = ':memory:'
const proxy = require(path.join(root, 'dotrino-proxy/server.js'))
const port = await proxy.start(0)
const proxyUrl = `ws://127.0.0.1:${port}`

const { startVault } = await import(path.join(root, 'dotrino-vault/src/vault.js'))
const { parseInvite } = await import(path.join(root, 'dotrino-vault/lib/src/invite.js'))
const idv = path.join(root, 'dotrino-identity/vault')
const { makeDeviceKey, makeDeviceEncKey } = await import(path.join(idv, 'capabilities.js'))
const { enrollDevice } = await import(path.join(idv, 'remote.js'))
const vault = await startVault({ dir: tmp('acta-e2e-'), proxyUrl, log: () => {} })

// El «teléfono»: su llave de perfil tal como la escribe la identidad (el JWK con sus campos).
const dev = await makeDeviceKey({ label: 'phone-acta' })
const enc = await makeDeviceEncKey()
const priv = await crypto.subtle.importKey('jwk', dev.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
const sign = async (t) => Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv, new TextEncoder().encode(t))).toString('base64')
const inv = await vault.startPairing({ scope: ['vault:sign'], label: 'phone-acta', ttlMs: 120_000 })
await enrollDevice({
  qr: typeof inv.qr === 'string' ? parseInvite(inv.qr) : inv.qr,
  device: { publickey: dev.publickey, sign }, encPub: enc.encPublickey, label: 'phone-acta',
  onChallenge: ({ code }) => { vault.approveDevice(code).catch((e) => console.error('approve failed', e)) },
})

const acta = async () => (await vault.identity.profileActa()).acta
await vault.setCaps(dev.publickey, ['sign', 'read'])
const actaSign = await acta()
await vault.setCaps(dev.publickey, ['read'])
const actaRead = await acta()

fs.writeFileSync(out, JSON.stringify({
  proxyUrl, publickey: dev.publickey, privateJwk: dev.privateJwk,
  encPub: enc.encPublickey, encPrivateJwk: enc.encPrivateJwk, actaSign, actaRead,
}))
console.log('e2e-acta: ready at', proxyUrl)

// Un espectador de la PWA mira lo que emita el perfil con `sign`.
const refFile = out + '.ref'
while (!fs.existsSync(refFile)) await new Promise((r) => setTimeout(r, 200))
const { Identity } = await import(path.join(root, 'dotrino-identity/src/node.js'))
const { WebSocketProxyClient } = await import(path.join(root, 'dotrino-proxy-client/src/index.js'))
const { createLobby, decodeBroadcastRef } = await import(path.join(root, 'dotrino-lobby/src/index.js'))
const identity = await Identity.connect({ dir: tmp('acta-viewer-') })
const lobby = await createLobby({ gameId: 'padel', identity, proxy: new WebSocketProxyClient({ url: proxyUrl, enableWebRTC: false }), url: proxyUrl })
const watching = await lobby.watchBroadcast(decodeBroadcastRef(fs.readFileSync(refFile, 'utf8').trim()))
watching.on('state', (state) => fs.appendFileSync(out + '.seen', JSON.stringify(state) + '\n'))

// La prueba borra <salida> al terminar. No se espera a cerrar la bóveda ni al espectador:
// sus sockets reintentan y el proceso no acabaría nunca.
while (fs.existsSync(out)) await new Promise((r) => setTimeout(r, 300))
process.exit(0)
