import java.io.File
import java.util.Base64

// LA APP DE IDENTIDAD de Android (docs/DISENO.md §2.2): guarda las llaves (Keystore), el
// almacén de la identidad y las cuentas, y atiende a las demás apps de Dotrino por un servicio
// con permiso de FIRMA. Lo más ligera posible: cada llamada de otra app pasa por aquí, así que
// nada de AppCompat, Material ni WebView.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// FIRMA DE RELEASE: la MISMA llave de subida que com.dotrino.app, de la bóveda (cajón `claude`):
//
//   dotrino-env run --ns claude -- ./gradlew --no-daemon :identity-app:bundleRelease
//
// El permiso del servicio es de nivel FIRMA: en el teléfono las dos apps tienen que llevar el
// mismo certificado. En Play eso lo decide la llave de firma de la app (al darla de alta se elige
// «la misma que com.dotrino.app»), no esta. Sin las variables el release sale sin firmar.
val uploadKey: Map<String, String>? = run {
    val b64 = System.getenv("ANDROID_UPLOAD_KEYSTORE_B64") ?: return@run null
    val dir = System.getenv("XDG_RUNTIME_DIR") ?: error("XDG_RUNTIME_DIR is not set: refusing to write the upload key to disk")
    val f = File(dir, "dotrino-upload-${ProcessHandle.current().pid()}.jks")
    f.writeBytes(Base64.getDecoder().decode(b64))
    f.setReadable(false, false); f.setReadable(true, true)
    f.deleteOnExit()
    mapOf(
        "storeFile" to f.absolutePath,
        "storePassword" to (System.getenv("ANDROID_UPLOAD_STORE_PASSWORD") ?: error("ANDROID_UPLOAD_STORE_PASSWORD missing")),
        "keyAlias" to (System.getenv("ANDROID_UPLOAD_KEY_ALIAS") ?: error("ANDROID_UPLOAD_KEY_ALIAS missing")),
        "keyPassword" to (System.getenv("ANDROID_UPLOAD_KEY_PASSWORD") ?: error("ANDROID_UPLOAD_KEY_PASSWORD missing")),
    )
}

android {
    namespace = "com.dotrino.identity"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dotrino.identity"
        minSdk = 31
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.2"
    }
    signingConfigs {
        if (uploadKey != null) {
            create("release") {
                storeFile = file(uploadKey.getValue("storeFile"))
                storePassword = uploadKey.getValue("storePassword")
                keyAlias = uploadKey.getValue("keyAlias")
                keyPassword = uploadKey.getValue("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            if (uploadKey != null) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":dotrino-native"))
}
