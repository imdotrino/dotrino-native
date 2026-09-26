// Vectores de oro para `dotrino-native`: los fabrica el PILAR JS de verdad
// (`@dotrino/identity`, del repo hermano), y las pruebas JUnit los reproducen. Si el pilar
// cambia un formato, regenerar esto hace que las pruebas nativas lo digan.
//
//   node test-vectors/gen.mjs   (desde la raíz del repo)
import { writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const id = join(here, '../../dotrino-identity/vault')
const { canonicalStringify } = await import(join(id, 'core.js'))
const { signWithDevice, makeDeviceKey, makeDeviceEncKey, signDelegationWith, pubkeyId, keyLabel } = await import(join(id, 'capabilities.js'))
const { makeContentKey, wrapForMember, encryptWithCek } = await import(join(id, 'content.js'))

// 1) Canónico: orden de claves, escapes, anidado, unicode sin escapar.
const canon = [
  { z: 1, a: 2, B: 3, b: [3, { d: 4, c: 5 }], e: null, f: true, g: false },
  { s: 'comillas " barra \\ salto\nretorno\rtab\tcontrol\u0001\u001f backspace\b formfeed\f' },
  { 'ñ': 'áéíóú ✓ 🔑', '': 'vacía', 'Z': [], 'a': {} },
  { op: 'identify', aud: 'wss://proxy.dotrino.com', publickey: '{"kty":"EC"}', token: 'AB12', ts: 1790000000000 },
].map((input) => ({ input, canonical: canonicalStringify(input) }))

// 2) Firma: una llave de aparato del pilar firma; la privada va en el vector para que la
//    prueba nativa firme con la MISMA llave y compare lo que verifica.
const dev = await makeDeviceKey()
const data = { op: 'approvals', publickey: dev.publickey, ts: 1790000000000, nested: { b: 1, a: [2, 1] } }
const { signature } = await signWithDevice({ privateJwk: dev.privateJwk, data })

// 3) Sobre: la bóveda sella el contexto de un pedido al `encPub` del aprobador.
const enc = await makeDeviceEncKey()
const cek = await makeContentKey()
const ctxPlain = JSON.stringify({ argv: ['dotrino-env', 'run', '--ns', 'claude'], cwd: '/home/ñandú', verified: 'proc' })
const ctxEnvelope = await encryptWithCek({ cek, gen: 0, plaintext: ctxPlain })
const ctxWrap = await wrapForMember({ cek, memberEncPub: enc.encPublickey })

// 4) Papel: la maestra de una bóveda delega en la llave del aparato.
const master = await crypto.subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify'])
const mjwk = await crypto.subtle.exportKey('jwk', master.publicKey)
const masterPub = JSON.stringify({ kty: mjwk.kty, crv: mjwk.crv, x: mjwk.x, y: mjwk.y })
const cert = await signDelegationWith(master.privateKey, masterPub, {
  sub: dev.publickey, scope: ['vault:sign', 'vault:approve'], iat: 1790000000000, seq: 37, nonce: 'n-1',
})

writeFileSync(join(here, '../Tests/DotrinoNativeTests/Resources/vectors.json'), JSON.stringify({
  canon,
  sign: { privateJwk: dev.privateJwk, publickey: dev.publickey, data, signature },
  sealed: { encPrivateJwk: enc.encPrivateJwk, encPub: enc.encPublickey, ctxWrap, ctxEnvelope, ctxPlain },
  cert: { master: masterPub, sub: dev.publickey, cert },
  keyid: { publickey: dev.publickey, id: await pubkeyId(dev.publickey), label: await keyLabel(dev.publickey) },
}, null, 2))
console.log('vectors.json written')
