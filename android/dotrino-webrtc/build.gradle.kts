// dotrino-webrtc: EL CAMINO DIRECTO (escalones 2 y 3 del transporte) para las apps nativas de
// Android. Aparte del núcleo porque libwebrtc pesa ~10 MB por arquitectura y no toda app lo
// quiere: la que sí, lo añade y lo enchufa con `SealedSession.useDirect(WebRtcDirect(ctx))`.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dotrino.sdk.webrtc"
    compileSdk = 36
    defaultConfig {
        minSdk = 31
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":dotrino-native"))
    // libwebrtc de Google, compilado y publicado en Maven Central (webrtc-sdk, BSD). Sin
    // servicios de Google ni telemetría: es la misma pila que trae el navegador.
    implementation("io.github.webrtc-sdk:android:150.7871.01")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
