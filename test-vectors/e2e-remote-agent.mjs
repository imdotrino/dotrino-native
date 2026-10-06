// Arnés de punta a punta del CLIENTE DE AGENTE REMOTO: levanta un PROXIO y una BÓVEDA de verdad
// (repos hermanos, desechables), empareja el «teléfono» y un AGENTE DE TERMINAL real
// (`dotrino-terminal/agent`, con su PTY), y lo deja corriendo. La prueba Kotlin
// (`RemoteAgentE2eTest`) lo encuentra con `probe`, hace el saludo, abre una consola y ejecuta
// un comando en ella.
//
//   node test-vectors/e2e-remote-agent.mjs <salida.json>
//   DOTRINO_E2E_AGENT=<salida.json> ./gradlew :dotrino-native:testDebugUnitTest --tests '*RemoteAgentE2eTest*'
//
// Se cierra solo a los 3 min.
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { createRequire } from 'node:module'
import { fileURLToPath } from 'node:url'

const out = process.argv[2]
if (!out) { console.error('usage: e2e-remote-agent.mjs <out.json>'); process.exit(2) }
const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.join(here, '../..')
const require = createRequire(import.meta.url)
const tmp = (n) => fs.mkdtempSync(path.join(os.tmpdir(), n))
setTimeout(() => { console.error('e2e-remote-agent: timeout'); process.exit(3) }, 180_000).unref()

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
const vault = await startVault({ dir: tmp('ra-e2e-vault-'), proxyUrl, log: () => {} })
const approve = ({ code }) => { vault.approveDevice(code).catch((e) => console.error('approve failed', e)) }
const invite = async (label) => { const inv = await vault.startPairing({ scope: ['vault:sign'], label, ttlMs: 120_000 }); return typeof inv.qr === 'string' ? parseInvite(inv.qr) : inv.qr }

// El «teléfono»: su llave de perfil tal como la escribe la identidad.
const dev = await makeDeviceKey({ label: 'phone' })
const enc = await makeDeviceEncKey()
const priv = await crypto.subtle.importKey('jwk', dev.privateJwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign'])
const sign = async (t) => Buffer.from(await crypto.subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, priv, new TextEncoder().encode(t))).toString('base64')
const phone = await enrollDevice({ qr: await invite('phone'), device: { publickey: dev.publickey, sign }, encPub: enc.encPublickey, label: 'phone', onChallenge: approve })

// El AGENTE DE TERMINAL, el de verdad: se enrola como cualquier máquina y abre shells con su PTY.
// El acta que tenía el teléfono al emparejarse, ANTES de que entrara el agente: el papel del
// agente sale de una posterior, y con esta el teléfono contesta `acta-vieja` (lo que le pasaba a
// la terminal de Android hasta que algo abría una página web de la identidad).
const staleActa = (await vault.identity.profileActa()).acta

const agentDir = tmp('ra-e2e-agent-')
const agentPkg = path.join(root, 'dotrino-terminal/agent')
const { enroll } = await import(path.join(agentPkg, 'link.js'))
const { startAgent } = await import(path.join(agentPkg, 'index.js'))
const link = await enroll({ qr: await invite('TerminalDePrueba'), dir: agentDir, onChallenge: approve })
const agent = await startAgent({ dir: agentDir, proxyUrl, shell: '/bin/sh', quiet: true })

// Con qué juzga el agente al teléfono: el acta que le manda la bóveda. Se espera a que la tenga.
const t0 = Date.now()
while (!JSON.parse(fs.readFileSync(path.join(agentDir, 'link.json'), 'utf8')).acta) {
  if (Date.now() - t0 > 20_000) { console.error('e2e-remote-agent: the agent never got the record'); process.exit(4) }
  await new Promise((r) => setTimeout(r, 200))
}
const acta = (await vault.identity.profileActa()).acta

fs.writeFileSync(out, JSON.stringify({
  proxyUrl, publickey: dev.publickey, privateJwk: dev.privateJwk, encPub: enc.encPublickey, encPrivateJwk: enc.encPrivateJwk,
  acta, staleActa, cert: phone.cert, master: phone.master, agentPubkey: link.device.publickey,
}))
console.log('e2e-remote-agent: ready at', proxyUrl, '· agent', agent.machineId)
