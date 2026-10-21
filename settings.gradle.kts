// Нютаг для Android: свой движок мессенджера без сторонних библиотек.
// Модули: crypto (Olm/Megolm, чистый Kotlin/JVM), core (протокол Matrix, двери, хранилище; чистый Kotlin/JVM),
// app (интерфейс Compose, звонки, пуши — Android).
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Nyutag"
include(":crypto", ":core", ":app")
