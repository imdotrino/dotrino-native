// dotrino-native: la parte del ecosistema que una app NATIVA necesita para hablar con la
// bóveda sin WebView. Es un puerto MÍNIMO del pilar JS (@dotrino/identity + el cable de
// @dotrino/proxy-client), y cada pieza se comprueba contra vectores que genera el propio
// pilar JS (`../../test-vectors/gen.mjs`): si el JS cambia, estas pruebas lo dicen.
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dotrino.sdk"
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
    testOptions { unitTests.isReturnDefaultValues = false }
    // Los MISMOS vectores de oro que prueba iOS: un solo archivo, sacado del pilar JS.
    sourceSets["test"].resources.srcDir("../../Tests/DotrinoNativeTests/Resources")
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
