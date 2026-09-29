// Arnés de punta a punta del RESPALDO DEL ALMACÉN en la bóveda (`VaultBackup`): levanta un PROXIO
// y una BÓVEDA de verdad (repos hermanos), SIEMBRA la bóveda con lo que habría escrito la PWA
// (un hilo de un contacto, más un hilo de OTRA app que el teléfono no debe traerse), enrola la
// llave «del teléfono» con permiso de almacén y le da el acta con el llavero de contenido.
//
//   node test-vectors/e2e-store.mjs <salida.json>
//   DOTRINO_E2E_STORE=<salida.json> ./gradlew :dotrino-native:testDebugUnitTest --tests '*VaultBackupE2eTest*'
//
// Escribe <salida.json> cuando está listo y <salida.json>.pushed cuando la bóveda ya tiene lo
// que escribió el teléfono (y NO tiene lo que el teléfono borró). Se cierra a los 3 minutos.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-store.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
setTimeout(() => { console.error('e2e-store: timeout'); process.exit(3) }, 180_000).unref()

process.env.NODE_ENV = 'test'
process.env.PROXY_DB_FILE = ':memory:'
const proxy = require(path.join(root, 'dotrino-proxy/server.js'))
const port = await proxy.start(0)
const proxyUrl = `ws://${process.env.E2E_HOST || '127.0.0.1'}:${port}`

const { startVault } = await import(path.join(root, 'dotrino-vault/src/vault.js'))
const { openThreadStore } = await import(path.join(root, 'dotrino-vault/src/threadStore.js'))
const { parseInvite } = await import(path.join(root, 'dotrino-vault/lib/src/invite.js'))
const idv = path.join(root, 'dotrino-identity/vault')
const { makeDeviceKey, makeDeviceEncKey } = await import(path.join(idv, 'capabilities.js'))
const { enrollDevice } = await import(path.join(idv, 'remote.js'))

// Lo que la PWA ya había guardado en la bóveda: un contacto con un mensaje (y uno que el
// teléfono va a borrar), y un hilo de otra app.
const contact = JSON.stringify({ kty: 'EC', crv: 'P-256', x: 'WEBCONTACTx', y: 'WEBCONTACTy' })
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'native-store-'))
openThreadStore(dir).methods.importThreads({
  threads: {
    [contact]: [
      { id: 'w1', ts: 1000, dir: 'in', text: 'desde la web ñ' },
      { id: 'w2', ts: 1001, dir: 'in', text: 'este se borra en el teléfono' },
    ],
    'padel.results': [{ id: 'p1', ts: 5, doc: { a: 1 } }],
  },
  tombs: {},
  mode: 'merge',
})

const vault = await startVault({ dir, proxyUrl, log: process.env.VAULT_LOG ? console.error : () => {} })
const dev = await makeDeviceKey({ label: 'phone-store' })
const enc = await makeDeviceEncKey()
const priv = await crypto.subtle.importKey('jwk', dev.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
const sign = async (t) => Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv, new TextEncoder().encode(t))).toString('base64')
const inv = await vault.startPairing({ scope: ['vault:sign', 'vault:store'], label: 'phone-store', ttlMs: 120_000, account: 'Cuenta E2E' })
const enrolled = await enrollDevice({
  qr: typeof inv.qr === 'string' ? parseInvite(inv.qr) : inv.qr,
  device: { publickey: dev.publickey, sign }, encPub: enc.encPublickey, label: 'phone-store',
  onChallenge: ({ code }) => { vault.approveDevice(code).catch((e) => console.error('approve failed', e)) },
})
await vault.setCaps(dev.publickey, ['sign', 'store', 'read'])
const acta = (await vault.identity.profileActa()).acta

fs.writeFileSync(out, JSON.stringify({
  proxyUrl, vault: enrolled.master, cert: enrolled.cert, deviceId: enrolled.deviceId,
  privateJwk: dev.privateJwk, encPrivateJwk: enc.encPrivateJwk, publickey: dev.publickey, acta, contact,
}))
console.log('ready', out)

// Lo que el teléfono sube tiene que acabar en el disco de la bóveda.
const tick = setInterval(() => {
  const t = openThreadStore(dir).methods.exportThreads().threads
  const th = t[contact] || []
  if (th.some((e) => e.id === 'n1') && !th.some((e) => e.id === 'w2')) {
    fs.writeFileSync(out + '.pushed', JSON.stringify({ contact: th, padel: t['padel.results'] || [] }))
    clearInterval(tick)
  }
}, 200)
setTimeout(async () => { try { vault.close() } catch (_) {} ; try { await proxy.stop() } catch (_) {} ; process.exit(0) }, 175_000)
