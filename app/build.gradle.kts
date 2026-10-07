plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing for UEM upload. Supply these as Gradle properties (~/.gradle/gradle.properties) or environment
// variables; never commit the keystore or its passwords. Without them, only debug builds are signed.
fun secret(name: String): String? = (findProperty(name) as String?) ?: System.getenv(name)

android {
    namespace = "com.aftersix.matrixrain"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.aftersix.matrixrain"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    signingConfigs {
        secret("MATRIXRAIN_KEYSTORE")?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = secret("MATRIXRAIN_KEYSTORE_PASSWORD")
                keyAlias = secret("MATRIXRAIN_KEY_ALIAS")
                keyPassword = secret("MATRIXRAIN_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core"))
}
