// Platform-neutral workload logic (ported from MatrixRain's Engine, WorkloadRequest and worker Program).
// Kept free of Android APIs so it runs as plain JVM unit tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // Keep test scratch files inside the build directory.
    val tmp = layout.buildDirectory.dir("tmp/test-scratch")
    doFirst { tmp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", tmp.get().asFile.absolutePath)
}
