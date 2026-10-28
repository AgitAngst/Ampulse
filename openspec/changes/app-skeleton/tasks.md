# Tasks

## 1. Проект и набор

- [ ] 1.1 Подмодуль `anvil/` (AgitAngst/Anvil на коммите набора 0.2.0), Gradle-проект в корне: `settings.gradle.kts` с `includeBuild("anvil/android")`, wrapper 9.7.1, `gradle/libs.versions.toml`, `gradle.properties` (`appVersion=0.2.0`), `.gitignore`. Проверка: `./gradlew :app:assembleGithubDebug --max-workers=2` с кодом 0.
- [ ] 1.2 `app/`: пакет `io.github.agitangst.ampulse`, сборка `github`, `versionName`/`versionCode`/`BUILD_NUMBER` по ANDROID.md §3, зависимости `anvil-compose` и `githubImplementation` `anvil-update`, `localeConfig`, `dataExtractionRules` (облако выключено). Проверка: тест формулы `versionCode` — зелёный.
- [ ] 1.3 Тема Ampulse: палитра из `docs/ui-guideline.md` §3 поверх набора, акцент `AMPULSE`. Проверка: тест контраста пар палитры Ampulse ≥ 4,5:1 — зелёный.

## 2. Оболочка

- [ ] 2.1 `MainActivity : AnvilActivity`, вкладки «Сегодня», «История», «Аптечка», «Близкие» с пустыми состояниями, экран «Настройки» («Оформление», «Обновления», «О программе»), баннер обновления, строки RU/EN. Проверка: `:app:lintGithubDebug` без ошибок; на телефоне — обе темы × оба языка.

## 3. Подпись и сборка на GitHub

- [ ] 3.1 Подпись выпуска: `keystore.properties`/переменные окружения, падение без ключа, `scripts/new-keystore.ps1`. Проверка: `assembleGithubRelease` без ключа падает с понятным текстом.
- [ ] 3.2 `.github/workflows/android.yml` по скилу `github-ci` (кнопка и тег `v*`, Ubuntu, JDK 21, подмодули, потолок времени; на тег — подписанный APK, `SHA256SUMS`, Release). Проверка: YAML разбирается; запуск — по правилу публичных репозиториев после версии.

## 4. Документы и сдача

- [ ] 4.1 `docs/README.md` (что работает), `docs/guide/install.md` (поставить APK, разрешение на установку), `docs/dev/development.md` (сборка, подпись, выпуск), `CLAUDE.md`, `HANDOFF.md`, `SPEC.md`. Проверка: ссылки ведут на существующие файлы.
- [ ] 4.2 Полный прогон: `./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug --max-workers=2`, затем `--stop`; код выхода 0.
