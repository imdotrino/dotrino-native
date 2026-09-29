// swift-tools-version:5.9
// dotrino-native para iOS: lo mínimo del ecosistema que una app NATIVA necesita para hablar
// con la bóveda (cripto, cable del proxio, cuentas y el almacén de la identidad). Mismo
// código que `android/dotrino-native`, mismos vectores de oro.
import PackageDescription

let package = Package(
    name: "DotrinoNative",
    defaultLocalization: "es",
    platforms: [.iOS("16.4")],
    products: [
        .library(name: "DotrinoNative", targets: ["DotrinoNative"]),
        // Los componentes de pantalla del ecosistema en SwiftUI (topbar, idioma). Aparte para
        // que el núcleo no dependa de SwiftUI (CONVENCIONES §16.2: una sola versión nativa).
        .library(name: "DotrinoNativeUI", targets: ["DotrinoNativeUI"]),
        // EL CAMINO DIRECTO (WebRTC). Aparte porque libwebrtc pesa ~10 MB por arquitectura y no
        // toda app lo quiere: la que sí, lo añade y hace `session.useDirect(WebRTCDirect())`.
        .library(name: "DotrinoNativeWebRTC", targets: ["DotrinoNativeWebRTC"]),
    ],
    dependencies: [
        // libwebrtc de Google, compilado (BSD). Sin servicios ni telemetría: la pila del navegador.
        .package(url: "https://github.com/stasel/WebRTC.git", exact: "153.0.0"),
    ],
    targets: [
        .target(name: "DotrinoNative"),
        .target(name: "DotrinoNativeUI", resources: [.process("Resources")]),
        .target(name: "DotrinoNativeWebRTC", dependencies: ["DotrinoNative", .product(name: "WebRTC", package: "WebRTC")]),
        .testTarget(
            name: "DotrinoNativeTests",
            dependencies: ["DotrinoNative", "DotrinoNativeUI"],
            // Los MISMOS vectores que prueba Android (`test-vectors/gen.mjs` los escribe aquí).
            resources: [.copy("Resources/vectors.json")]
        ),
    ]
)
