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
  { subject: 'S', rating: 4.5, notes: '', low: -0.25, tiny: 0.001, whole: 5 },
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

// 5) Perfil: una identidad del pilar (la de Node, el mismo core que el navegador) le escribe
//    al «teléfono» con `encrypt` (sobre v2): la prueba nativa lo abre con `Profile.decrypt`.
//    Lo contrario (el teléfono sella y el navegador abre) lo prueba e2e-broadcast.mjs.
const { Identity } = await import(join(here, '../../dotrino-identity/src/node.js'))
const { mkdtempSync } = await import('node:fs')
const { tmpdir } = await import('node:os')
const sender = await Identity.connect({ dir: mkdtempSync(join(tmpdir(), 'vec-id-')) })
const phoneEnc = await makeDeviceEncKey()
const profilePlain = JSON.stringify({ __ccl: 1, g: 'padel', r: '_k', k: 'bcast.watch', d: { secret: 'ñ-s', at: null } })
const profileEnvelope = await sender.encrypt([{ encryptionPubkey: phoneEnc.encPublickey }], profilePlain)
const senderEncPub = await sender.getEncryptionPubkey()

// 5b) Mensaje SELLADO de una app (el messenger): `identitySealing` del pilar del transporte,
//     `{ app, sealed, from }`. El teléfono lo abre con `IdentitySealing.open`.
const { identitySealing } = await import(join(here, '../../dotrino-proxy-client/src/sealing.js'))
const sealedMsg = { type: 'DM', text: 'hola ñandú ✓', ts: 1790000000000, mid: 'm-1' }
const appSealed = await identitySealing(sender, { app: 'messenger' }).seal(sealedMsg, phoneEnc.encPublickey)

// 5b2) TODO EN BINARIO (proxy-client ≥ 0.35): un mensaje con bytes dentro, sellado con el sobre
//      binario (la CEK sellada con `identitySealing`, el paquete cifrado como bytes) y metido en
//      la trama binaria del proxio, tal cual lo arma el pilar. El teléfono lo desempaqueta y lo
//      abre; los bytes le llegan como base64 dentro del JSON.
const pcSrc = join(here, '../../dotrino-proxy-client/src')
const { pack, encodeFrame } = await import(join(pcSrc, 'binpack.js'))
const { setSealingPrimitives, makeMessageKey, encryptBytes, BIN_SEALED } = await import(join(pcSrc, 'sealing.js'))
setSealingPrimitives(await import(join(id, 'content.js')))
const fileBytes = new Uint8Array([0, 1, 2, 127, 128, 250, 255])
const binMsg = { type: 'DM', text: 'archivo ñ', file: { name: 'a.bin', mime: 'application/octet-stream', data: fileBytes } }
const binCek = await makeMessageKey()
const binK = await identitySealing(sender, { app: 'messenger' }).seal({ cek: binCek }, phoneEnc.encPublickey)
const binCt = await encryptBytes(binCek, pack(binMsg))
const binEnv = pack({ bv: BIN_SEALED, k: binK, iv: binCt.iv, ct: binCt.ct })
const binFrame = encodeFrame({ type: 'message', from: 'TOK-A', from_publickey: 'PK-A', queued: true, queued_at: 1790000000000 }, binEnv)
const b64 = (u8) => Buffer.from(u8).toString('base64')
const binSealed = {
  app: 'messenger', frame: b64(binFrame), senderEncPub,
  resolved: { ...binMsg, file: { ...binMsg.file, data: b64(fileBytes) } },
  // Un paquete SIN sellar con bytes (lo que manda un canal que cifra por su cuenta).
  plainPacked: b64(pack({ type: 'ra.data', n: 3, blob: fileBytes, list: [fileBytes, 'x'] })),
  plainResolved: { type: 'ra.data', n: 3, blob: b64(fileBytes), list: [b64(fileBytes), 'x'] },
}

// 5c) Libro de contactos: una TARJETA de perfil firmada por su master y una CALIFICACIÓN
//     firmada por una identidad. El teléfono las verifica (PeerBook).
const { makeProfileCard } = await import(join(id, 'acta.js'))
const cardMaster = await makeDeviceKey()
const cardDev = await makeDeviceKey(); const cardDevEnc = await makeDeviceEncKey()
const cardActa = {
  profileId: cardMaster.publickey, seq: 3, sealedBy: cardMaster.publickey, updatedAt: 1790000000000,
  members: [
    { pub: cardDev.publickey, encPub: cardDevEnc.encPublickey, caps: ['sign'] },
    { pub: 'SVC', encPub: 'x', caps: ['read'], cn: 'svc' },
  ],
}
const card = await makeProfileCard({ acta: cardActa, privateJwk: cardMaster.privateJwk })
const ratedSubject = cardDev.publickey
await sender.setRating(ratedSubject, 4.5, 'buen trato ñ')
const endorsement = (await sender.getRatingsForSubject(ratedSubject)).mine

// 5d) Un PERFIL CON ACTA de verdad (génesis sellada por el pilar): lo que un registro exige
//     para aceptar una firma (la cadena prueba que el aparato habla por la persona).
const { genesisActa, sealActa } = await import(join(id, 'acta.js'))
const repSign = await makeDeviceKey(); const repEnc = await makeDeviceEncKey()
const repActa = await sealActa({ acta: genesisActa({ pub: repSign.publickey, encPub: repEnc.encPublickey, label: 'phone' }), privateJwk: repSign.privateJwk })

// 5e) Almacén: la HUELLA de un hilo y el PLAN de conciliación, tal cual los calculan el
//     navegador y la bóveda (`@dotrino/store/core` + `vault-sync.js`). Si el teléfono no da
//     byte por byte la misma huella, la sincronización se repite para siempre en silencio.
const storeCore = await import(join(here, '../../dotrino-store/store/core.js'))
const { planThread } = await import(join(here, '../../dotrino-store/src/vault-sync.js'))
const digestCases = [
  [{ id: 'b', ts: 2 }, { id: 'a', ts: 1 }, { id: 'ñ', ts: 1790000000000 }, { id: 'Z', ts: 0 }, { id: '10', ts: 3 }],
  [{ id: 'solo', ts: 5, text: 'lo demás no cuenta' }],
]
const digests = []
for (const entries of digestCases) digests.push({ entries, digest: await storeCore.threadDigest(entries) })
const planCases = [
  { local: { items: [['a', 1], ['b', 5], ['c', 3]], tombs: [['d', 2, 9]] }, remote: { items: [['a', 1], ['b', 4], ['d', 2], ['e', 7]], tombs: [['c', 3, 8]] }, max: 1000 },
  { local: { items: [['x', 10], ['y', 20]], tombs: [] }, remote: { items: [['old', 5], ['new', 30]], tombs: [] }, max: 2 },
]
const plans = planCases.map((c) => ({ ...c, plan: planThread(c.local, c.remote, c.max) }))

// 6) Acta: qué puede un miembro (capacidades, renuncias del acta y propias, lo desconocido).
const { memberCan } = await import(join(id, 'acta.js'))
const actaA = {
  members: [
    { pub: 'P1', caps: ['sign', 'read', 'future-cap'] },
    { pub: 'P2', caps: ['read'] },
    { pub: 'P3', caps: ['sign', 'store'], cn: 'svc' },
  ],
  renounced: [{ member: 'P3', caps: ['store'] }],
}
const acta = []
for (const [pub, cap, extra] of [['P1', 'sign'], ['P1', 'future-cap'], ['P2', 'sign'], ['P3', 'sign'], ['P3', 'store'],
  ['P1', 'read', [{ member: 'P1', caps: ['read'] }]], ['P9', 'sign']]) {
  acta.push({ pub, cap, extra: extra || [], can: memberCan(actaA, pub, cap, extra || []) })
}

// 7) Emisión de lobby: el enlace (`key.secret.x.y`) y el nombre del canal.
const lobby = join(here, '../../dotrino-lobby/src')
const { encodeBroadcastRef } = await import(join(lobby, 'broadcast.js'))
const { broadcastChannel } = await import(join(lobby, 'protocol.js'))
const hostPub = dev.publickey
const broadcast = [
  { key: 'ABCDEFGH1234xYz_-9', secret: 's3cr3t-_', hostPubkey: hostPub },
  { key: '_noNode_key', secret: 'sec', hostPubkey: hostPub },
].map((r) => ({ ...r, encoded: encodeBroadcastRef(r), channel: broadcastChannel('padel', r.key) }))

// 8) Ponerse al día con el acta (`ActaSync`): una cadena REAL sellada por el pilar, y lo que el
//    pilar contesta en cada caso — verificar, adoptar y el hash. La prueba nativa tiene que dar
//    exactamente las mismas razones: si no, el teléfono adoptaría lo que la web rechaza o al revés.
const ActaJs = await import(join(id, 'acta.js'))
const sA = await makeDeviceKey({ label: 'sealer' }); const eA = await makeDeviceEncKey()
const sB = await makeDeviceKey({ label: 'phone' }); const eB = await makeDeviceEncKey()
const sC = await makeDeviceKey({ label: 'stranger' })
const g1 = await ActaJs.sealActa({ acta: ActaJs.genesisActa({ pub: sA.publickey, encPub: eA.encPublickey, label: 'pc' }), privateJwk: sA.privateJwk })
const g2 = await ActaJs.sealActa({ acta: await ActaJs.applyChanges(g1, [{ op: 'admit', member: { pub: sB.publickey, encPub: eB.encPublickey, caps: ['sign', 'store'], label: 'phone' } }], { by: sA.publickey, now: 1790000000000 }), privateJwk: sA.privateJwk })
const g3 = await ActaJs.sealActa({ acta: await ActaJs.applyChanges(g2, [{ op: 'caps', pub: sB.publickey, caps: ['sign', 'store', 'read'] }], { by: sA.publickey, now: 1790000001000 }), privateJwk: sA.privateJwk })
// Lo que NO se debe adoptar: tocado después de firmar, sin encadenar, sellado por un extraño.
const tampered = { ...g2, members: g2.members.map((m) => ({ ...m, caps: [...m.caps, 'admin'] })) }
const unchained = await ActaJs.sealActa({ acta: { ...ActaJs.actaBody(g2), prev: '0'.repeat(64) }, privateJwk: sA.privateJwk })
const stranger = await ActaJs.sealActa({ acta: { ...ActaJs.actaBody(g3), sealedBy: sC.publickey }, privateJwk: sC.privateJwk }).catch(() => null)
const named = { g1, g2, g3, tampered, unchained, ...(stranger ? { stranger } : {}) }
const actaCases = []
for (const [cand, cur] of [['g2', 'g1'], ['g3', 'g1'], ['g3', 'g2'], ['g1', 'g2'], ['g2', 'g2'], ['tampered', 'g1'], ['unchained', 'g1'], ['stranger', 'g2'], ['g1', null]]) {
  if (!named[cand]) continue
  const r = await ActaJs.canAdopt({ candidate: named[cand], current: cur ? named[cur] : null })
  actaCases.push({ candidate: cand, current: cur, adopt: r.adopt, reason: r.reason })
}
const actaVerify = []
for (const [k, a] of Object.entries(named)) actaVerify.push({ name: k, reason: (await ActaJs.verifyActa({ acta: a })).ok ? null : (await ActaJs.verifyActa({ acta: a })).reason })
// `adoptChain` de la identidad, sobre una lista desordenada y con basura: se ordena por `seq` y
// cada eslabón se juzga contra el último adoptado (core.js). Lo que gana lo dice el pilar.
const chainOrder = ['g3', 'tampered', 'g2', 'unchained', 'stranger'].filter((k) => named[k])
let chainCur = g1; let chainWon = null
for (const k of [...chainOrder].sort((x, y) => named[x].seq - named[y].seq)) {
  if ((await ActaJs.canAdopt({ candidate: named[k], current: chainCur })).adopt) { chainCur = named[k]; chainWon = k }
}
const actaHashes = Object.fromEntries(await Promise.all(Object.entries(named).map(async ([k, a]) => [k, await ActaJs.actaHash(a)])))

// 12) BLUETOOTH (proxy-client docs/bluetooth.md): la llave del canal sale de un ECDH efímero
//     + HKDF; un registro sellado con un contador fijo es determinista (AES-GCM), y los trozos
//     hacia un central llevan su etiqueta. Las privadas van en el vector para reproducirlo.
const BW = await import(join(pcSrc, 'ble/wire.js'))
const ecdhPair = async () => {
  const kp = await crypto.subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits'])
  return { kp, jwk: await crypto.subtle.exportKey('jwk', kp.privateKey), epub: BW.b64u(new Uint8Array(await crypto.subtle.exportKey('raw', kp.publicKey))) }
}
const bleA = await ecdhPair(); const bleB = await ecdhPair()
const bleTokA = 'tok-A9'; const bleTokB = 'tok-b1'
const bleSecret = await crypto.subtle.deriveBits({ name: 'ECDH', public: bleB.kp.publicKey }, bleA.kp.privateKey, 256)
const bleIkm = await crypto.subtle.importKey('raw', bleSecret, 'HKDF', false, ['deriveBits'])
const [bleLo, bleHi] = bleTokA < bleTokB ? [bleTokA, bleTokB] : [bleTokB, bleTokA]
const bleRaw = new Uint8Array(await crypto.subtle.deriveBits({ name: 'HKDF', hash: 'SHA-256', salt: new TextEncoder().encode(bleLo + '|' + bleHi), info: new TextEncoder().encode('dotrino-ble-v1') }, bleIkm, 256))
const bleAes = await crypto.subtle.importKey('raw', bleRaw, 'AES-GCM', false, ['encrypt', 'decrypt'])
// La llave del pilar (no exportable) abre lo que sella la del vector: son la misma.
const bleKeyB = await BW.deriveKey(bleB.kp.privateKey, bleA.epub, bleTokB, bleTokA)
const blePlain = '{"hola":"ñandú ✓"}'
const bleRecord = await BW.sealRecord(bleAes, BW.DIR_TO_CENTRAL, 5, BW.TYPE_TEXT, BW.encodeText(blePlain))
if (BW.decodeText((await BW.openRecord(bleKeyB, BW.DIR_TO_CENTRAL, 5, bleRecord)).body) !== blePlain) throw new Error('ble vector: keys differ')
const bleBid = 'q83vEjRWeJA'
const ble = {
  a: { privateJwk: bleA.jwk, epub: bleA.epub, token: bleTokA },
  b: { privateJwk: bleB.jwk, epub: bleB.epub, token: bleTokB },
  key: b64(bleRaw),
  record: { dir: BW.DIR_TO_CENTRAL, counter: 5, type: BW.TYPE_TEXT, plain: blePlain, sealed: b64(bleRecord) },
  bid: bleBid,
  tag: b64(BW.tagOf(bleBid)),
  chunks: BW.toChunks(bleRecord, 20, BW.tagOf(bleBid)).map(b64),
  rxChunks: BW.toChunks(bleRecord, 20).map(b64),
  hello: b64(BW.encodeHello(bleBid)),
  beacon: b64(BW.encodeBeacon('pair', 'cINVITE')),
  contact: (() => {
    const publickey = JSON.stringify({ crv: 'P-256', kty: 'EC', x: 'f83OJ3D2xF1Bg8vub9tLe1gHMzV76e8Tus9uPHvRVEU', y: 'x_FEzRu9m36HLN_tue659LNpXW6pCyStikYjKIWI5a0' })
    // Lo que cada lado tiene que escribir igual: espacios, acentos, reservados, un emoji, y el recorte por bytes.
    return ["Ana María & co (ñ)!*'~", '', '😀'.repeat(30), '中'.repeat(40)].map((nickname) => {
      const data = BW.encodeContact({ publickey, nickname })
      return { publickey, nickname, data, decodedNick: BW.decodeContact(data).nickname }
    })
  })(),
  uuids: { service: BW.SERVICE_UUID, hello: BW.HELLO_UUID, beacon: BW.BEACON_UUID, rx: BW.RX_UUID, tx: BW.TX_UUID },
}

// 7b) LA RED LOCAL (dotrino-proxy-client/docs/lan.md): las cadenas del TXT, en orden, para
// una carga corta, una de varios trozos con letras de dos bytes en el corte, y la más larga.
const LW = await import(join(pcSrc, 'lan/mdns.js'))
const lan = {
  service: LW.SERVICE,
  cases: [['contact', 'to=abc&nick=Prueba%20%C3%B1'], ['pair', 'c' + 'é'.repeat(200)], ['pair', 'x'.repeat(LW.MAX_DATA)]]
    .map(([kind, data]) => ({ kind, data, txt: LW.txtEntries(kind, data).map(b64) })),
}

writeFileSync(join(here, '../Tests/DotrinoNativeTests/Resources/vectors.json'), JSON.stringify({
  profile: { encPrivateJwk: phoneEnc.encPrivateJwk, encPub: phoneEnc.encPublickey, encKeyId: (await pubkeyId(phoneEnc.encPublickey)).slice(0, 16), senderEncPub, envelope: profileEnvelope, plain: profilePlain },
  appSealed: { app: 'messenger', envelope: appSealed, msg: sealedMsg, senderEncPub },
  binSealed,
  peers: { card, cardDevPub: cardDev.publickey, cardDevEncPub: cardDevEnc.encPublickey, endorsement, subject: ratedSubject },
  store: { digests, plans },
  actaProfile: { signPrivateJwk: repSign.privateJwk, encPrivateJwk: repEnc.encPrivateJwk, acta: repActa },
  acta: { acta: actaA, cases: acta },
  actaSync: { actas: named, adopt: actaCases, verify: actaVerify, hashes: actaHashes, chain: { from: 'g1', order: chainOrder, won: chainWon } },
  broadcast,
  canon,
  sign: { privateJwk: dev.privateJwk, publickey: dev.publickey, data, signature },
  sealed: { encPrivateJwk: enc.encPrivateJwk, encPub: enc.encPublickey, ctxWrap, ctxEnvelope, ctxPlain },
  cert: { master: masterPub, sub: dev.publickey, cert },
  keyid: { publickey: dev.publickey, id: await pubkeyId(dev.publickey), label: await keyLabel(dev.publickey) },
  ble,
  lan,
}, null, 2))
console.log('vectors.json written')
