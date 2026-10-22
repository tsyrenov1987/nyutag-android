// Приложение «Нютаг» для Android. Зависимости — только Kotlin/корутины и AndroidX/Jetpack,
// плюс Google Play Billing для пожертвований и Firebase Cloud Messaging для пушей (исключения по слову владельца).
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "chat.nyutag.messenger"
    compileSdk = 37

    defaultConfig {
        applicationId = "chat.nyutag.messenger"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "2.0"
        // Firebase без плагина google-services: те же четыре строки ресурсов, по ним FirebaseInitProvider
        // поднимает FCM сам. Файл не в git; без него сборка идёт, но пушей нет.
        firebase(file("google-services.json"))?.forEach { (name, value) -> resValue("string", name, value) }
    }

    buildTypes {
        debug { applicationIdSuffix = ".dev" }
        release { isMinifyEnabled = false }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
        resValues = true
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.google.billing)
    implementation(libs.google.firebase.messaging)
}

/** Строки ресурсов Firebase из google-services.json (клиент с пакетом релиза). */
fun firebase(json: File): Map<String, String>? {
    if (!json.exists()) return null
    @Suppress("UNCHECKED_CAST")
    val root = groovy.json.JsonSlurper().parse(json) as Map<String, Any?>
    val project = root["project_info"] as Map<String, Any?>
    val client = (root["client"] as List<Map<String, Any?>>).first {
        ((it["client_info"] as Map<String, Any?>)["android_client_info"] as Map<String, Any?>)["package_name"] == "chat.nyutag.messenger"
    }
    return mapOf(
        "google_app_id" to (client["client_info"] as Map<String, Any?>)["mobilesdk_app_id"] as String,
        "google_api_key" to ((client["api_key"] as List<Map<String, Any?>>).first()["current_key"] as String),
        "gcm_defaultSenderId" to project["project_number"] as String,
        "project_id" to project["project_id"] as String,
    )
}
