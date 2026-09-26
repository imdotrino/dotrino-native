// LA APP DE IDENTIDAD de Android (docs/DISENO.md §2.2): guarda las llaves (Keystore), el
// almacén de la identidad y las cuentas, y atiende a las demás apps de Dotrino por un servicio
// con permiso de FIRMA. Lo más ligera posible: cada llamada de otra app pasa por aquí, así que
// nada de AppCompat, Material ni WebView.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dotrino.identity"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.dotrino.identity"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = true
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
