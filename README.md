# dotrino-native

Lo mínimo del ecosistema Dotrino que una app **nativa** (iOS y Android) necesita para hablar
con la bóveda sin WebView: JSON canónico, firma y sobres, el cable del proxio, las cuentas y
el almacén de la identidad. Es un **puerto** de `@dotrino/identity` y `@dotrino/proxy-client`:
si el pilar JS cambia un formato, esto lo sigue.

Diseño y decisiones (perfil compartido entre apps, app de identidad en Android): [`docs/DISENO.md`](docs/DISENO.md).

## Estructura

```
Package.swift, Sources/, Tests/   iOS (Swift Package, CryptoKit, llaves en el Secure Enclave)
android/dotrino-native/           Android (librería Kotlin, llaves en el Android Keystore)
test-vectors/                     vectores de oro y arneses de punta a punta (Node, repos hermanos)
```

Mismas piezas en las dos plataformas (el nombre Kotlin; en Swift es igual salvo `EnclaveKeys`):

| Archivo | Qué es | Original en JS |
|---|---|---|
| `Canonical.kt` | JSON canónico (lo que se firma) | `vault/core.js` `canonicalStringify` |
| `Crypto.kt` | JWK, firma P1363, `openWrap` / `decryptWithCek` | `vault/capabilities.js`, `vault/content.js` |
| `Delegation.kt` | `pubkeyId`, `keyLabel`, cuerpo y comprobación del papel | `vault/keyid.js`, `delegationBody` |
| `KeystoreKeys.kt` | las dos llaves de una cuenta en el Android Keystore | — |
| `ProxyConnection.kt` | `connected` / `identify` / `push-subscribe` / mensaje por pubkey / `networkStats` (0.27.0: una conexión suelta también sale en el modal de red del topbar, registrándola en `DotrinoNetwork`) / `NetworkStats.report()` (0.29.0: el modal de red copia las cifras como texto) / `useDirect` + `sendToOrUpgrade` + `enableTurn` (0.28.0: la conexión suelta también sube a WebRTC; `RemoteAgent.Session` habla por token en cuanto conoce el del agente, como el cliente JS) | `proxy-client/src/client.js` |
| `VaultClient.kt` | `approvals` / `approve` / `deny` / `block` (0.26.0: un incidente se bloquea o se ignora) / `grants` / `renew` | `vault/remote.js` `vaultRpc` |
| `AccountStore.kt` | las cuentas en disco, cifradas con una llave del Keystore | — |
| `DotrinoStore.kt` | el almacén de la app en el aparato: hilos de entradas con id, lápidas, sellado; una caché por app | `@dotrino/store` (el respaldo en la bóveda es `VaultBackup`) |

**Hablar como el perfil del teléfono** (para compartir en vivo y lo que venga):

| Pieza | Qué es | Original en JS |
|---|---|---|
| `Profile` | la identidad ACTIVA del teléfono leída del almacén de la identidad (`kv:`/`key:`), con su política: firma solo si el acta le da `sign` (si no, `needs-vault-signer`: firmar por la bóveda aún no está portado); `encrypt`/`decrypt` (sobre v2) | `@dotrino/identity` `signData`, `encrypt`, `decrypt` |
| `Acta` | `memberCan` / `effectiveCaps` (solo leer) | `vault/acta.js` |
| `PhoneIdentity` (Android) / `Profile.fromPhone()` (iOS) | el perfil del teléfono: por la app de identidad en Android, por el almacén compartido del equipo en iOS; y la llave del TRANSPORTE de la app (firma sus canales) | — |
| `ProxyConnection` (+) | el saludo (`helloTo`), canales (`publish`), anunciar y averiguar llaves de cifrado verificadas (`announceEncPub`, `encPubOf`), mandar por token | `proxy-client` |
| `BroadcastHost` | la EMISIÓN de lobby, lado del emisor: sellada a cada espectador y firmada por el perfil; quien mira lo hace en la web | `@dotrino/lobby` `broadcast.js` |

Probado de punta a punta contra un proxio real y un espectador que corre `@dotrino/lobby` tal
cual (`test-vectors/e2e-broadcast.mjs` + `BroadcastE2eTest`, también desde iOS por la LAN), y
en el emulador contra la app de identidad real (`PhoneIdentityDeviceTest`). La política del acta,
con actas **reales** de una bóveda desechable (`test-vectors/e2e-acta.mjs` + `ActaE2eTest`): con
`sign` el perfil emite y el espectador lo verifica; tras quitarle `sign`, `needs-vault-signer`.

**Hablar con tus otros aparatos** (lo que usa la terminal nativa, y cualquier app que maneje un
agente — terminal, IA — corriendo en otra máquina de la cuenta):

| Pieza | Qué es | Original en JS |
|---|---|---|
| `RemoteAgent` | el lado del CLIENTE de un agente remoto: `probe` (quién está encendido y qué es, sin encolar ni timbrar), `open` (saludo firmado con el papel del perfil; el ack del agente se juzga contra el acta: lo emitió una selladora y no nombra un acta más nueva) y la `Session` (canal por sesión ECDH P-256 → HKDF → AES-GCM; el proxio solo ve `{ type, sid, env }`) | `@dotrino/remote-agent` `client.js`, `e2e.js`, `discover.js` |
| `ActaSync` | pone al día el acta del teléfono desde la bóveda (`vault.devices` con `sinceSeq`), la adopta eslabón a eslabón con las reglas del pilar (`verifyActa`, `canAdopt`) y la guarda donde la lee la identidad; `RemoteAgent.open(…, catchUp)` la usa una vez ante `acta-vieja` | `@dotrino/identity` `vault/acta.js`, `core.js` (`adoptChain`) |
| `Acta.sealersOf` | quién puede sellar el acta (y por tanto de quién valen los papeles) | `vault/acta.js` `sealersOf` |

Comprobado contra un vector que sella el propio JS (`Tests/…/Resources/remote-agent.json` +
`RemoteAgentTest`) y de punta a punta contra un agente de terminal REAL enrolado en una bóveda
desechable (`test-vectors/e2e-remote-agent.mjs` + `RemoteAgentE2eTest`: lo encuentra, hace el
saludo, abre una consola y ejecuta un comando). Falta el puerto a Swift.

**Hablar con otras personas** (lo que usa el messenger nativo, y cualquier app que mande algo
del usuario por mensaje dirigido — CONVENCIONES §4.1):

| Pieza | Qué es | Original en JS |
|---|---|---|
| `IdentitySealing` | el sobre `{ app, sealed, from }`: sellado con el sobre de la identidad a TODAS las llaves de la persona, y dice quién selló | `proxy-client` `identitySealing` |
| `SealedSession` | una conexión que se mantiene (reconecta, re-identifica, re-anuncia la llave, cambia de proxio tras 3 fallos) y exige el sellado en las dos direcciones; código corto (`requestPairingCode`/`redeemPairingCode`) y `whoIs` | `WebSocketProxyClient` con `requireSealed` |
| `PeerBook` | el libro de contactos del perfil (el MISMO registro que la identidad: `peers:peers.<pid>.v1`), tarjetas de perfil, calificaciones firmadas y avales verificados | `vault/peerStore.js` + `vault/core.js` |
| `DirectTransport` | el camino directo (escalones 2 y 3): la interfaz; libwebrtc va aparte en **`dotrino-webrtc`** (Android) / **`DotrinoNativeWebRTC`** (iOS) porque pesa ~10 MB por arquitectura | `webrtc.js` |
| `Reputation` | el registro de reputación (`rep.dotrino.com`): calificar por eje firmado con la cadena del acta, releer lo propio y ponderar por la red de confianza | `createVaultReputation` |
| `VaultBackup` | el respaldo del almacén en la bóveda: concilia por huella por hilo, solo los hilos de la app, cifrado con la clave de contenido | `@dotrino/store` `vault-sync.js` + `store/core.js` |
| `Compat` | declarar la versión y juzgar la del otro (§14) | `@dotrino/compat` |
| `DotrinoQr` / `QrScanView` · `DotrinoQR` / `DotrinoQRScanner` | mostrar y leer QR, todo en el aparato (Android: ZXing core; iOS: CoreImage + AVFoundation) | `@dotrino/qr` |

El camino directo se probó contra el pilar JS real (`test-vectors/e2e-direct.mjs` +
`DirectE2eTest` en el emulador y `DirectE2eTests` en el simulador): el primer mensaje sale por
el proxio, el canal se abre por debajo y el siguiente va por WebRTC, sellado igual.

El respaldo se probó contra una bóveda de verdad (`test-vectors/e2e-store.mjs` +
`VaultBackupE2eTest`/`VaultBackupE2eTests`), y sus reglas contra el propio pilar (huella y plan en
`vectors.json`). La barra nativa lleva el **botón de perfil** (§6.1): abre la app Dotrino, que es
donde el teléfono administra sus perfiles.

**Pantalla** (CONVENCIONES §16.2: los componentes del ecosistema existen UNA vez, aquí):
Android en `com.dotrino.sdk.ui` (vistas nativas: `DotrinoTopbar` con `brand` y `actions`,
`DotrinoLocale`, `DotrinoApps`, `DotrinoSheet` —el modal del ecosistema— e `IdentityRequired`:
si falta la app de identidad, primero un modal que lo explica y después Play); iOS en el producto aparte **`DotrinoNativeUI`** (SwiftUI:
`DotrinoTopbar`, `DotrinoLang`), para que el núcleo no dependa de SwiftUI.

**Avisos y cómo se ven** (2026-09-30):

| Pieza | Qué es | Original en JS |
|---|---|---|
| `DotrinoPush` (iOS, `DotrinoNativeUI`) | pide permiso, registra el teléfono en APNs y da el `PushToken` (entorno sandbox/producción leído del perfil de firma); `SealedSession.setPushToken` lo sube al proxio tras cada `identify`. El proxio (≥ 1.3.0) timbra por **APNs directo**, sin Firebase, con `loc-key` (`DOTRINO_RING_TITLE`/`BODY` en el `Localizable.strings` de la app) | `push-subscribe` de `proxy-client` |
| `DotrinoRing` | **el trino**: UN sonido para todos los avisos del ecosistema (`sound/trinos/trino-01a.wav`, convertido solo de formato). iOS: `DotrinoPush` lo copia a `Library/Sounds` y el proxio lo pone en cada aviso (`dotrino-ring.caf`); con la app abierta lo toca `DotrinoRing.play()`. Android: canales MUDOS (`DotrinoRing.channel`) y `play()` al avisar, al 45 %, respetando silencio y «No molestar» | — |
| `Presence` | **«¿eres tú?»** (0.24.0): el aviso del propio teléfono —huella o cara, o el código de bloqueo— justo antes de una acción que pesa (aprobar un pedido, ver un secreto, cambiar permisos). **Apagado por defecto**: la persona lo enciende donde quiera (`turn` / `isOn`, que también confirman antes de apagarlo) y la app pregunta con `confirmIfOn`. `Presence.confirm(…)` contesta confirmado, cancelado, **no disponible** (el teléfono no tiene bloqueo: la acción NO se hace) o el fallo del sistema. Es una comprobación en la app, antes de la llamada: no ata la llave del chip. iOS pide `NSFaceIDUsageDescription` en el Info.plist de la app | — (en la web no existe todavía) |
| `DotrinoAvatar` + `DotrinoAvatarView` | el avatar del perfil: la foto (`me.avatar`) o el identicon de `@dotrino/identity/avatar`, el MISMO (vectores de la web en `AvatarTest`/`AvatarTests`). `Profile.name`/`avatar`/`avatarSeed` salen del almacén de la identidad; `Profile.topbar` lo da a la barra | `vault/avatar.js` |
| `DotrinoTopbar(showProfile:)` | `false` para una pantalla de TODOS los perfiles (Pedidos de la app Dotrino) | — |
| `TrafficStats` + `DotrinoNetwork` + el botón ⇅ de `DotrinoTopbar` | **estadísticas de red** (0.20.0): cada `SealedSession` cuenta lo que entra y sale por conexión y por camino (proxy, WebRTC directo, WebRTC por TURN; la ruta sale del par ICE de `getStats`) y se apunta en `DotrinoNetwork` al arrancar. La barra enseña el botón solo si hay una sesión viva y abre una hoja que se refresca cada segundo. La app no cablea nada | `stats.js` de `proxy-client` 0.28 + el modal de `@dotrino/topbar` 0.14 |

**Un token muerto no se traga el mensaje:** `sendSealed(toToken:…, peerPubkey:)` escucha el
`message_sent.failed` del proxio y, si el token ya no existe, manda el MISMO sobre por pubkey
(`sendToOrElse`), como `proxy-client` 0.26.

**Android, entre apps en trozos:** Identidad ↔ Dotrino/Messenger pasan por Binder, que no
admite más de ~512 KB por mensaje (y un String viaja en UTF-16). Petición y respuesta se parten en
trozos de 100 000 caracteres (`IdentityClient.split` + `Joiner`, Identidad ≥ 0.2.4): el almacén
de la identidad con fotos de perfil pasaba de ese tope y las páginas se quedaban sin perfiles.

**No hace el emparejamiento.** El alta la hace el pilar JS de siempre (la consola, con
`enrollDevice` y un firmador externo), firmando con la llave del chip.

## Pruebas

```sh
node test-vectors/gen.mjs            # regenera Tests/DotrinoNativeTests/Resources/vectors.json desde el pilar JS
cd android && ./gradlew :dotrino-native:testDebugUnitTest          # Android (JVM)
xcodebuild -scheme DotrinoNative -destination 'platform=iOS Simulator,name=iPhone 16' test   # iOS (en una Mac)
# El llavero y el Secure Enclave se prueban ALOJADOS en una app (el simulador exige el
# entitlement): esas pruebas viven en la app que usa la librería (dotrino-app, KeysAndStoreTests).

# contra un proxio y una bóveda REALES (repos hermanos dotrino-proxy, dotrino-vault, dotrino-identity):
node test-vectors/e2e-vault.mjs /tmp/e2e.json &
DOTRINO_E2E=/tmp/e2e.json ./gradlew :dotrino-native:testDebugUnitTest
```

MIT.
