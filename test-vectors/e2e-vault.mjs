// Arnés de punta a punta para `dotrino-native`: levanta un PROXIO y una BÓVEDA de verdad
// (los de los repos hermanos), enrola una llave de aparato con el pilar JS —firmando por el
// camino del firmador externo, que es el mismo que usa el teléfono— y deja un pedido de
// escritura pendiente. La prueba Kotlin (`VaultE2eTest`) se conecta, renueva su papel (no
// trae `vault:approve` hasta que se le concede), ve el pedido, lo abre y lo aprueba.
//
//   node test-vectors/e2e-vault.mjs <salida.json>
//   E2E_HOST=192.168.x.y node … <salida.json>   (proxio alcanzable desde otra máquina: iOS, ver ios/README)
//
// Escribe <salida.json> cuando está listo, y <salida.json>.written cuando la bóveda guardó
// la variable aprobada. Se cierra solo a los 3 minutos.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-vault.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))

process.env.NODE_ENV = 'test'
process.env.PROXY_DB_FILE = ':memory:'
process.env.DOTRINO_VAULT_NOTICE_MS = '150'

const proxy = require(path.join(root, 'dotrino-proxy/server.js'))
const port = await proxy.start(0)
// E2E_HOST: la IP de esta máquina en la LAN, para probar desde otra (el simulador de iOS en la Mac).
const proxyUrl = `ws://${process.env.E2E_HOST || '127.0.0.1'}:${port}`

const { startVault } = await import(path.join(root, 'dotrino-vault/src/vault.js'))
const { parseInvite } = await import(path.join(root, 'dotrino-vault/lib/src/invite.js'))
const { buildSealedVar, authorFromDeviceKey } = await import(path.join(root, 'dotrino-vault/lib/src/admin.js'))
const idv = path.join(root, 'dotrino-identity/vault')
const { makeDeviceKey, makeDeviceEncKey } = await import(path.join(idv, 'capabilities.js'))
const { enrollDevice } = await import(path.join(idv, 'remote.js'))

const vault = await startVault({ dir: tmp('native-e2e-'), proxyUrl, log: process.env.VAULT_LOG ? console.error : () => {} })

// La llave «del teléfono». Su privada viaja al archivo SOLO porque esto es una prueba: la
// prueba Kotlin firma con ella en software. En el teléfono vive en el Keystore.
const dev = await makeDeviceKey({ label: 'phone-native' })
const enc = await makeDeviceEncKey()
const priv = await crypto.subtle.importKey('jwk', dev.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
const sign = async (texto) => Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv, new TextEncoder().encode(texto))).toString('base64')

const inv = await vault.startPairing({ scope: ['vault:sign'], label: 'phone-native', ttlMs: 120_000, account: 'Cuenta E2E' })
const qr = typeof inv.qr === 'string' ? parseInvite(inv.qr) : inv.qr
const enrolled = await enrollDevice({
  qr, device: { publickey: dev.publickey, sign }, encPub: enc.encPublickey, label: 'phone-native',
  onChallenge: ({ code }) => { vault.approveDevice(code).catch((e) => console.error('approve failed', e)) },
})
// Aprueba: se le concede DESPUÉS de enrolar, como en la vida real. Su papel no lo lleva.
await vault.setCaps(dev.publickey, ['sign', 'approve'])

// Otro teléfono al que el dueño NO le ha dado `+aprueba`: el caso del 2026-09-25, que en
// pantalla salía como «invalid renewed paper: scope» y no decía qué hacer.
const dev2 = await makeDeviceKey({ label: 'phone-no-approve' })
const enc2 = await makeDeviceEncKey()
const inv2 = await vault.startPairing({ scope: ['vault:sign'], label: 'phone-no-approve', ttlMs: 120_000, account: 'Cuenta E2E' })
const priv2 = await crypto.subtle.importKey('jwk', dev2.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
const enrolled2 = await enrollDevice({
  qr: typeof inv2.qr === 'string' ? parseInvite(inv2.qr) : inv2.qr,
  device: { publickey: dev2.publickey, sign: async (t) => Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv2, new TextEncoder().encode(t))).toString('base64') },
  encPub: enc2.encPublickey, label: 'phone-no-approve',
  onChallenge: ({ code }) => { vault.approveDevice(code).catch((e) => console.error('approve failed', e)) },
})

// Una consola sin `unattended` guarda una variable: queda pendiente de aprobación.
const consola = await makeDeviceKey()
await vault.identity.admitMember({ pub: consola.publickey, label: 'consola', caps: ['sign', 'admin'] })
const ns = 'nativo'
const recipients = await vault.vars.recipients({ ns })
const sealed = await buildSealedVar({ recipients, owner: `ns:${ns}`, key: 'API_TOKEN', value: 'secreto', author: authorFromDeviceKey(consola) })
const r = await vault.vars.setMany({ ns, items: [{ key: 'API_TOKEN', sealed }], caller: consola.publickey, by: 'e2e' })
if (!r.pending) throw new Error('the write did not stay pending')

fs.writeFileSync(out, JSON.stringify({
  proxyUrl, vault: enrolled.master, cert: enrolled.cert, deviceId: enrolled.deviceId,
  privateJwk: dev.privateJwk, encPrivateJwk: enc.encPrivateJwk, pending: r.pending, ns, account: enrolled.account,
  noApprove: { cert: enrolled2.cert, deviceId: enrolled2.deviceId, privateJwk: dev2.privateJwk, encPrivateJwk: enc2.encPrivateJwk },
}))
console.log('ready', out)

const tick = setInterval(() => {
  if ((vault.listSecrets()[ns] || []).some((k) => k.key === 'API_TOKEN')) {
    fs.writeFileSync(out + '.written', 'ok')
    clearInterval(tick)
  }
}, 150)
setTimeout(async () => { try { vault.close() } catch (_) {} ; try { await proxy.stop() } catch (_) {} ; process.exit(0) }, 180_000)
