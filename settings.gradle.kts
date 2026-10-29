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
        // Tesseract (распознавание текста на фото, кириллица) есть только на JitPack — и только он оттуда.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

// Модули набора подставляются по группе io.github.agitangst.anvil.
includeBuild("anvil/android")

include(":app")
