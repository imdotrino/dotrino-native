# dotrino-native — diseño

> Decidido con el dueño el 2026-09-26, al empezar las apps nativas (iOS y Android) del
> ecosistema: **cada app que valga la pena existe en tres versiones — web, iOS y Android —
> y en un teléfono todas son UN SOLO aparato del acta.**

## 1. Qué es esto

Lo mínimo del ecosistema que una app nativa necesita para hablar con la bóveda sin WebView:
JSON canónico, firma y sobres (el puerto de `@dotrino/identity`), el cable de
`@dotrino/proxy-client`, las cuentas y el almacén de la identidad. **Es un puerto, no decide
nada propio**: si el pilar JS cambia un formato, esto lo sigue, y los vectores de oro
(`test-vectors/gen.mjs`, sacados del pilar) lo hacen saltar en las dos plataformas.

Una sola pieza para todas las apps nativas, por la misma razón que los `@dotrino/*` en la web:
**ninguna app reimplementa la cripto ni el protocolo.**

## 2. El perfil se comparte entre las apps de un teléfono

Emparejar UNA app deja dentro a todas: misma llave, misma lista de perfiles, mismos papeles.
No hay un aparato por app ni una aprobación en la bóveda por cada una.

### 2.1 iOS: grupos compartidos del equipo

Apple deja compartir entre apps del mismo equipo (DOTRINO S.A.S., `P7G853375S`):

| Qué | Con qué |
|---|---|
| Llaves (Secure Enclave) | **Keychain Access Group** `P7G853375S.com.dotrino.shared` |
| Almacén de la identidad y cuentas | **App Group** `group.com.dotrino` (los archivos sellados van a su contenedor) |

Cada app firma ella misma con la llave del chip. No hace falta tener otra app instalada.

En código: la app llama a `SharedStorage.share(keychainAccessGroup:appGroup:)` al arrancar,
antes de tocar ninguna llave ni almacén. Sin los grupos en sus entitlements, se para con su
error en vez de seguir con un almacén propio (que sería otro aparato sin decirlo).

### 2.2 Android: una app de identidad, lo más ligera posible

Una llave del Android Keystore es de UNA app y no se comparte. Así que la identidad vive en
una app aparte, **`com.dotrino.identity`**, que no hace nada más:

- guarda las llaves (Keystore) y el almacén de la identidad y las cuentas;
- atiende un **servicio** protegido con un permiso de nivel **firma**
  (`com.dotrino.permission.IDENTITY`, `protectionLevel="signature"`): solo lo pueden usar
  apps firmadas con el mismo certificado;
- habla el MISMO protocolo que el puente del WebView (`create`, `open`, `sign`, `deriveBits`,
  `save`, `remove`, `storeLoad`, `storeSet`, `storeRemove`), solo que por IPC.

**Ligera, porque cada llamada de las otras apps pasa por ella**: sin WebView, sin dependencias
pesadas, sin trabajo al arrancar. El servicio se enlaza una vez por app y se queda enlazado
mientras la app está en primer plano; una firma es una llamada, no un arranque.

Sin la app de identidad instalada, la app **lo dice y ofrece instalarla** (Google Play). No
se empareja por su cuenta: dos caminos para lo mismo es lo que la regla de simplificar evita.

### 2.3 Lo que hay que decidir ANTES de publicar cada app

1. **Android — la llave de firma de Play.** El permiso de firma exige el mismo certificado.
   Con Play App Signing se elige al CREAR la app en Play Console: «usar la misma llave de
   firma que otra app de esta cuenta» → la de `com.dotrino.identity`. Después no se cambia.
2. **iOS — equipo y grupos.** Toda app en el equipo `P7G853375S`, con el Keychain Access
   Group y el App Group de arriba en sus entitlements.

## 3. Cómo lo consume una app

- **iOS**: Swift Package por URL de git con versión (`https://github.com/imdotrino/dotrino-native`).
- **Android**: `includeBuild` del proyecto `android/` (el repo como submódulo de la app) y
  `implementation("com.dotrino:dotrino-native")`. Pendiente: publicarlo en Maven Central
  desde CI, con la misma disciplina que npm (tag → CI firma y publica).

## 4. Estado (2026-09-26)

| | Hecho | Falta |
|---|---|---|
| Librería | movida desde `dotrino-app` (Swift + Kotlin), pruebas en verde; grupos compartidos en iOS (`SharedStorage`, 0.2.0) | cliente del servicio (Android) |
| App de identidad Android | — | todo |
| `dotrino-app` | usa esta librería; en iOS, con los grupos compartidos | en Android, pasar a la app de identidad |
