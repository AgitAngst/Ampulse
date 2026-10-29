// Приложение Ampulse. Kotlin с AGP 9 встроен — отдельного плагина Kotlin для Android нет.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Схемы базы — в репозитории: по ним проверяются миграции.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

// Версия: X.Y.Z из gradle.properties; versionCode = (X·10000 + Y·100 + Z)·100 + K, K = 99 у выпуска, N у -beta.N.
val appVersion = providers.gradleProperty("appVersion").get()
val appVersionCode: Int = run {
    val (core, pre) = appVersion.split("-", limit = 2).let { it[0] to it.getOrNull(1) }
    val (x, y, z) = core.split(".").map { it.toInt() }
    val k = pre?.substringAfterLast('.')?.toIntOrNull()?.also { require(it in 1..98) { "pre-release number must be 1..98: $appVersion" } } ?: 99
    (x * 10000 + y * 100 + z) * 100 + k
}

// Номер сборки — число коммитов; без git (архив исходников) — 0.
val buildNumber: Int = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

// Ключ выпуска — у владельца, не в репозитории: keystore.properties рядом с проектом или переменные окружения (CI).
val keyProps = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secret(prop: String, env: String): String? = keyProps.getProperty(prop) ?: System.getenv(env)
val releaseKey = listOf(
    secret("store", "AMPULSE_KEYSTORE"),
    secret("storePassword", "AMPULSE_KEYSTORE_PASSWORD"),
    secret("alias", "AMPULSE_KEY_ALIAS"),
    secret("keyPassword", "AMPULSE_KEY_PASSWORD"),
).takeIf { it.all { v -> !v.isNullOrBlank() } }

android {
    namespace = "io.github.agitangst.ampulse"
    compileSdk = 37
    compileSdkMinor = 2

    defaultConfig {
        applicationId = "io.github.agitangst.ampulse"
        minSdk = 29
        targetSdk = 37
        versionName = appVersion
        versionCode = appVersionCode
        buildConfigField("int", "BUILD_NUMBER", buildNumber.toString())
    }

    // Сборки по магазину (anvil/docs/ANDROID.md, §2): APK из GitHub Releases, RuStore, Google Play (AAB).
    flavorDimensions += "store"
    productFlavors {
        create("github") { dimension = "store" }
        create("rustore") { dimension = "store" }
        create("play") { dimension = "store" }
    }
    // У rustore и play одинаково всё, что отличает их от github (обновляет магазин, SMS — через приложение сообщений).
    sourceSets {
        getByName("rustore").kotlin.directories += "src/stores/java"
        getByName("play").kotlin.directories += "src/stores/java"
    }
    // Play раскладывает AAB по языкам — тогда выбор языка внутри программы не находит строк (§2).
    bundle {
        language { enableSplit = false }
    }

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey[0]!!)
                storePassword = releaseKey[1]
                keyAlias = releaseKey[2]
                keyPassword = releaseKey[3]
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("en", "ru")
    }
}

// Без ключа выпуска нет: отладочная подпись наружу не уходит — поверх неё потом не встанет настоящая.
tasks.matching { it.name.matches(Regex("(assemble|bundle|package)\\w*Release")) }.configureEach {
    doFirst {
        if (releaseKey == null) {
            throw GradleException(
                "Release signing key is missing: put keystore.properties next to the project or set AMPULSE_KEYSTORE, " +
                    "AMPULSE_KEYSTORE_PASSWORD, AMPULSE_KEY_ALIAS, AMPULSE_KEY_PASSWORD (docs/dev/development.md)",
            )
        }
    }
}

dependencies {
    implementation(libs.anvil.compose)
    // Обновление из GitHub — только в сборке github: с ним приходят разрешения на сеть и установку.
    "githubImplementation"(libs.anvil.update)
    implementation(libs.activity.compose)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.tesseract)
    implementation(libs.zxing.core)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.org.json.test)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.withType<Test> {
    useJUnitPlatform()
}
