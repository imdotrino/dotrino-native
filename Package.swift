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
    ],
    targets: [
        .target(name: "DotrinoNative"),
        .target(name: "DotrinoNativeUI", resources: [.process("Resources")]),
        .testTarget(
            name: "DotrinoNativeTests",
            dependencies: ["DotrinoNative", "DotrinoNativeUI"],
            // Los MISMOS vectores que prueba Android (`test-vectors/gen.mjs` los escribe aquí).
            resources: [.copy("Resources/vectors.json")]
        ),
    ]
)
