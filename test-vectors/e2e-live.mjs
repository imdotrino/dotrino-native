// Prueba A MANO de la app (emulador o teléfono) contra DOS bóvedas de usar y tirar, en el
// proxio que se le diga (por defecto el de producción, que es el que ve la consola).
//
//   node test-vectors/e2e-live.mjs <dir> [wss://proxy.dotrino.com]
//
// Por cada cuenta (A y B) escribe en <dir>:
//   invite-<A|B>.txt   el enlace para abrir en la app (`/d#v=…`)
// y espera en <dir>:
//   code-<A|B>.txt     los seis dígitos que enseña el teléfono → la bóveda los aprueba
// Cuando el teléfono entra, le concede `aprueba` y deja un pedido de escritura pendiente.
// Escribe written-<A|B>.txt cuando la variable aprobada queda guardada, y denied-<A|B>.txt
// si el pedido desaparece sin guardarse (denegado). Se cierra solo a los 20 minutos.
//
// Más pedidos, con el teléfono ya dentro:
//   more-<A|B>.txt  → otro pedido de escritura; escribe more-<A|B>.ts con la hora en que nació
//                     (para medir cuánto tarda en verse en el teléfono)
//   read-<A|B>.txt  → un servicio pide sus claves (pedido de lectura); read-done-<A|B>.txt al
//                     recibirlas, read-denied-<A|B>.txt si se deniega
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const dir = process.argv[2]
const proxyUrl = process.argv[3] || 'wss://proxy.dotrino.com'
if (!dir) { console.error('usage: e2e-live.mjs <dir> [proxy]'); process.exit(2) }
fs.mkdirSync(dir, { recursive: true })
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))

const { startVault } = await import(path.join(root, 'dotrino-vault/src/vault.js'))
const { inviteUrl } = await import(path.join(root, 'dotrino-vault/lib/src/invite.js'))
const { buildSealedVar, authorFromDeviceKey } = await import(path.join(root, 'dotrino-vault/lib/src/admin.js'))
const { makeDeviceKey } = await import(path.join(root, 'dotrino-identity/vault/capabilities.js'))
const { enrollWithVault, fetchSecrets } = await import(path.join(root, 'dotrino-vault/lib/src/service.js'))
const exists = (f) => fs.existsSync(path.join(dir, f))
const put = (f, v) => fs.writeFileSync(path.join(dir, f), String(v))

async function account (name) {
  const vault = await startVault({ dir: tmp(`live-${name}-`), proxyUrl, log: process.env.VAULT_LOG ? console.error : () => {} })
  const inv = await vault.startPairing({ scope: ['vault:sign'], label: 'phone', ttlMs: 15 * 60_000, account: `Prueba ${name}` })
  // `startPairing` devuelve el objeto del QR; el enlace es el mismo que imprime la bóveda.
  fs.writeFileSync(path.join(dir, `invite-${name}.txt`), inviteUrl(inv.qr))
  console.log(name, 'invite written')

  const codeFile = path.join(dir, `code-${name}.txt`)
  while (!fs.existsSync(codeFile)) await new Promise((r) => setTimeout(r, 300))
  const code = fs.readFileSync(codeFile, 'utf8').trim()
  const r = await vault.approveDevice(code)
  const sub = r?.cert?.sub
  if (!sub) throw new Error(`${name}: approve gave no cert`)
  console.log(name, 'enrolled', r.deviceId || '')
  await vault.setCaps(sub, ['sign', 'approve'])

  const consola = await makeDeviceKey()
  await vault.identity.admitMember({ pub: consola.publickey, label: `consola ${name}`, caps: ['sign', 'admin'] })
  const ns = `app-${name.toLowerCase()}`
  const write = async (key) => {
    const recipients = await vault.vars.recipients({ ns })
    const sealed = await buildSealedVar({ recipients, owner: `ns:${ns}`, key, value: 'x', author: authorFromDeviceKey(consola) })
    return vault.vars.setMany({ ns, items: [{ key, sealed }], caller: consola.publickey, by: 'live' })
  }
  const key = `TOKEN_${name}`
  const w = await write(key)
  console.log(name, 'pending write', w.pending)

  // Un servicio con su cajón y SIN `unattended`: pedir sus claves pide aprobación.
  const svcNs = `svc-${name.toLowerCase()}`
  const sinv = await vault.startPairing({ scope: ['vault:sign', `vault:secrets:${svcNs}`], label: `servicio ${name}`, ttlMs: 60_000 })
  let ok = null
  const svc = await enrollWithVault({ qr: sinv.qr, label: `servicio ${name}`, onCode: ({ code }) => { ok = vault.approveDevice(code) } })
  await ok
  await vault.setSecret(svcNs, 'DB_PASSWORD', 'secreto')

  let n = 0
  const watcher = setInterval(async () => {
    if (exists(`more-${name}.txt`)) {
      fs.rmSync(path.join(dir, `more-${name}.txt`))
      const r = await write(`EXTRA_${name}_${++n}`)
      put(`more-${name}.ts`, Date.now()); console.log(name, 'more', r.pending)
    }
    if (exists(`read-${name}.txt`)) {
      fs.rmSync(path.join(dir, `read-${name}.txt`))
      fetchSecrets({ ns: svcNs, proxyUrl, masterPubkey: vault.master, device: svc.device, cert: svc.cert, enc: svc.enc, timeoutMs: 5 * 60_000 })
        .then((v) => { put(`read-done-${name}.txt`, Object.keys(v).join(',')); console.log(name, 'READ OK') })
        .catch((e) => { put(`read-denied-${name}.txt`, e.message); console.log(name, 'READ', e.message) })
      console.log(name, 'read asked')
    }
  }, 200)

  const t = setInterval(() => {
    if ((vault.listSecrets()[ns] || []).some((k) => k.key === key)) {
      fs.writeFileSync(path.join(dir, `written-${name}.txt`), 'ok'); console.log(name, 'WRITTEN'); clearInterval(t)
    } else if (!vault.listApprovals().some((p) => p.id === w.pending)) {
      fs.writeFileSync(path.join(dir, `denied-${name}.txt`), 'ok'); console.log(name, 'DENIED'); clearInterval(t)
    }
  }, 300)
  return vault
}

const vaults = await Promise.all([account('A'), account('B')])
setTimeout(() => { vaults.forEach((v) => { try { v.close() } catch (_) {} }); process.exit(0) }, 20 * 60_000)
