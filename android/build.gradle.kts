plugins {
    id("com.android.application") version "8.9.1" apply false
    id("com.android.library") version "8.9.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0" apply false
}
// Una app lo consume con `includeBuild` (ver README): el grupo es lo que sustituye.
allprojects { group = "com.dotrino" }
