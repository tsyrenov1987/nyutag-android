// Своя реализация Olm/Megolm: Curve25519/Ed25519 на чистом Kotlin, AES/HMAC/SHA-256 из JDK (javax.crypto).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
