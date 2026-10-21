// Протокол Matrix: HTTP через двери, вход, /sync, хранилище, медиа, крипто-машина. Без UI и без Android API.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin { jvmToolchain(21) }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

dependencies {
    api(project(":crypto"))
    api(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test { useJUnitPlatform() }
