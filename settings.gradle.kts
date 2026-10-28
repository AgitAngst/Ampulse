// Ampulse — приложение под Android. Набор Anvil (тема, настройки, обновление) — из подмодуля anvil/,
// правила — anvil/docs/ANDROID.md.
rootProject.name = "ampulse"

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

// Модули набора подставляются по группе io.github.agitangst.anvil.
includeBuild("anvil/android")

include(":app")
