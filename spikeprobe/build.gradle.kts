// Phase 0 probe: a second, unprivileged app that tries to reach the spike's guarded components.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aftersix.matrixrain.spikeprobe"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aftersix.matrixrain.spikeprobe"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-probe"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
