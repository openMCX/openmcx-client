//
// OpenMCX Licence Information : AGPL-3.0-or-later
//

// Protocol engine: pure Kotlin on the JVM, no Android dependencies.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.coroutines.core)
    api(libs.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
