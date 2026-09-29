// Arnés de punta a punta del CAMINO DIRECTO nativo (`WebRtcDirect` + `SealedSession`) contra el
// PROXIO de producción (uno local no emite códigos: le falta identidad de nodo) y un cliente JS
// del pilar tal cual (`@dotrino/proxy-client`
// con WebRTC en JS puro, `@dotrino/webrtc`, sellado con `identitySealing`). El teléfono canjea
// su código, le escribe sellado, y el cliente JS contesta a cada mensaje diciendo POR DÓNDE le
// llegó (`proxy` o `webrtc`).
//
//   node test-vectors/e2e-direct.mjs [wss://proxy.dotrino.com]   (imprime la URL y el código)
//   adb shell am instrument -w -e url <url> -e code <código> \
//     -e class com.dotrino.sdk.webrtc.DirectE2eTest com.dotrino.sdk.webrtc.test/androidx.test.runner.AndroidJUnitRunner
//
// Se cierra solo a los 3 minutos.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
setTimeout(() => { console.error('e2e-direct: timeout'); process.exit(3) }, 180_000).unref()

const proxyUrl = process.argv[2] || 'wss://proxy.dotrino.com'

const { WebSocketProxyClient, identitySealing } = await import(path.join(root, 'dotrino-proxy-client/src/index.js'))
const { setPeerConnection } = await import(path.join(root, 'dotrino-proxy-client/src/webrtc.js'))
const { RTCPeerConnection } = await import(path.join(root, 'dotrino-webrtc/lib/index.mjs'))
setPeerConnection(RTCPeerConnection)
const { Identity } = await import(path.join(root, 'dotrino-identity/src/node.js'))

const id = await Identity.connect({ dir: fs.mkdtempSync(path.join(os.tmpdir(), 'e2e-direct-')) })
const client = new WebSocketProxyClient({
  url: proxyUrl,
  requireSealed: true,
  myEncPub: await id.getEncryptionPubkey(),
  sealing: identitySealing(id, { app: 'messenger' }),
})
await client.connect()
await client.identifyAs({ publickey: id.me.publickey, sign: (d) => id.signData(d) })

client.on('webrtc_open', (t) => console.log('webrtc open with', t.slice(0, 12)))
client.on('message', async (from, payload, meta) => {
  const via = meta.via || 'proxy'
  console.log('got', JSON.stringify(payload), 'via', via, 'sealed', meta.sealed)
  if (payload?.type !== 'PING') return
  try { await client.sendSealedTo(from, { type: 'PONG', n: payload.n, gotVia: via }) }
  catch (e) { console.error('reply failed:', e.code, e.message) }
})

const { code } = await client.requestPairingCode({ ttlMs: 170_000 })
console.log(`READY url=${proxyUrl} code=${code}`)
