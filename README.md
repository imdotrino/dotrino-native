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
| `ProxyConnection.kt` | `connected` / `identify` / `push-subscribe` / mensaje por pubkey | `proxy-client/src/client.js` |
| `VaultClient.kt` | `approvals` / `approve` / `deny` / `grants` / `renew` | `vault/remote.js` `vaultRpc` |
| `AccountStore.kt` | las cuentas en disco, cifradas con una llave del Keystore | — |
| `DotrinoStore.kt` | el almacén de la app en el aparato: hilos de entradas con id, sellado; una caché por app | `@dotrino/store` (sin el espacio por perfil ni el respaldo en la bóveda, todavía) |

**Pantalla** (CONVENCIONES §16.2: los componentes del ecosistema existen UNA vez, aquí):
Android en `com.dotrino.sdk.ui` (vistas nativas: `DotrinoTopbar` con `brand` y `actions`,
`DotrinoLocale`, `DotrinoApps`); iOS en el producto aparte **`DotrinoNativeUI`** (SwiftUI:
`DotrinoTopbar`, `DotrinoLang`), para que el núcleo no dependa de SwiftUI.

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
