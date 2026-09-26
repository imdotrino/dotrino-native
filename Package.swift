// swift-tools-version:5.9
// dotrino-native para iOS: lo mínimo del ecosistema que una app NATIVA necesita para hablar
// con la bóveda (cripto, cable del proxio, cuentas y el almacén de la identidad). Mismo
// código que `android/dotrino-native`, mismos vectores de oro.
import PackageDescription

let package = Package(
    name: "DotrinoNative",
    platforms: [.iOS("16.4")],
    products: [.library(name: "DotrinoNative", targets: ["DotrinoNative"])],
    targets: [
        .target(name: "DotrinoNative"),
        .testTarget(
            name: "DotrinoNativeTests",
            dependencies: ["DotrinoNative"],
            // Los MISMOS vectores que prueba Android (`test-vectors/gen.mjs` los escribe aquí).
            resources: [.copy("Resources/vectors.json")]
        ),
    ]
)
