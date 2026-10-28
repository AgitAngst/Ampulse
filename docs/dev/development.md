# Разработка Ampulse

Как получить исходники, собрать и проверить приложение, поднять версию, подписать и выпустить. Общие правила
Android-программ семьи — `docs/ANDROID.md` в репозитории [Anvil](https://github.com/AgitAngst/Anvil)
(инструменты, версия, подпись, выпуск, обновление); здесь — как они выглядят в этом репозитории.

## Что понадобится

- Git, JDK 21 (Temurin) и Android SDK. На ПК владельца они стоят один раз и только читаются:
  `JAVA_HOME=D:\Android\jdk-21`, `ANDROID_HOME=D:\Android\Sdk`. Всё, что собирается, лежит в папке
  репозитория (на диске сборок E:).
- Gradle ставить не надо: версия 9.7.1 скачивается через `gradlew`. Версии AGP, Kotlin и Compose — в
  `gradle/libs.versions.toml`, они общие для всех Android-программ семьи и меняются сразу везде.
- PowerShell 5.1 — для скрипта ключа (`scripts/new-keystore.ps1`).

## Клонирование и набор Anvil

Общий Android-набор (тема, «Оформление», «О программе», обновление из GitHub) лежит в отдельном
репозитории Anvil и подключён **подмодулем `anvil/`**; Gradle подставляет его модули по группе
`io.github.agitangst.anvil` (`includeBuild("anvil/android")` в `settings.gradle.kts`). Без подмодуля проект
не соберётся.

```sh
git clone --recurse-submodules https://github.com/AgitAngst/Ampulse.git
# клонировали без --recurse-submodules (или подмодуль пуст):
git submodule update --init
```

**Какая версия набора.** Сейчас подмодуль стоит на коммите набора 0.2.0 (`c1e3c0e` в Anvil). Когда владелец
поставит в Anvil тег `android-kit-v0.2.0`, подмодуль переводится на тег; после этого — только на теги вида
`android-kit-vX.Y.Z`.

**Как обновить подмодуль** (по слову владельца):

```sh
git -C anvil fetch --tags
git -C anvil checkout android-kit-v0.2.0    # тег (пока тега нет — нужный коммит)
git add anvil
git commit -m "Pin Anvil Android kit to android-kit-v0.2.0"
```

Подмодуль запоминает коммит, а не ветку: после `git pull` в Ampulse выполните `git submodule update --init`,
иначе `anvil/` останется на старом коммите.

## Сборка и проверка на ПК

В PowerShell, в папке репозитория:

```powershell
$env:JAVA_HOME = 'D:\Android\jdk-21'
$env:ANDROID_HOME = 'D:\Android\Sdk'
./gradlew :app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug --max-workers=2
./gradlew --stop
```

- `--max-workers=2` и `./gradlew --stop` после работы — чтобы Gradle не занимал память ПК (`gradle.properties`
  держит `-Xmx2g`). Gradle и cargo одновременно не запускать.
- Отладочный APK — `app/build/outputs/apk/github/debug/app-github-debug.apk`. Он подписан отладочным ключом
  Android, поверх выпуска (и наоборот) не встанет.
- Сборка одна — `github` (APK из GitHub Releases, с обновлением изнутри); сборки для RuStore и Google Play
  появятся позже (этап 0.9).
- Судить о результате по коду выхода команды; вывод длинный — писать в файл.
- На телефоне — установка по adb и снимки (`E:\dev_personal\tools\phone.py`): светлая и тёмная тема, русский и
  английский. Снимки в публичный репозиторий — только в демо-режиме, без настоящих данных.

## Версия

Версия живёт в одном месте — строка `appVersion=X.Y.Z` в `gradle.properties`. Из неё в
`app/build.gradle.kts` считаются:

- `versionName` — `X.Y.Z` (пред-выпуск — `X.Y.Z-beta.N`, `-rc.N`, `-alpha.N`);
- `versionCode` = `(X·10000 + Y·100 + Z)·100 + K`, где `K` = 99 у выпуска и `N` у пред-выпуска (1–98):
  `0.2.0` → `20099`, `0.2.0-beta.3` → `20003`. Число растёт с каждой версией, а пред-выпуск меньше своего
  выпуска — этого требует установщик Android;
- номер сборки — число коммитов (`git rev-list --count HEAD`) в `BuildConfig.BUILD_NUMBER`; в «О программе»
  показывается мелко рядом с версией. Нет git (архив исходников) — 0. На GitHub история берётся целиком
  (`fetch-depth: 0`), иначе номер был бы 1.

Схема версий: этап плана ([SPEC.md](../../SPEC.md), «Этапы кода») — вторая цифра +1, исправление — третья,
после 0.9.x идёт 1.0.0.

## Подпись

Выпуск подписывается **одним ключом на всё время жизни приложения**. Потерянный ключ — новая версия больше не
встанет поверх установленной ни у кого; ключ не лежит в репозитории, копия хранится во втором месте.

**Создать ключ** (один раз, владелец):

```powershell
powershell -ExecutionPolicy Bypass -File scripts\new-keystore.ps1
```

Скрипт дважды спрашивает пароль (минимум 8 печатных ASCII-символов; ввод скрыт, нигде не печатается), создаёт
ключ RSA 4096 на 36500 дней с псевдонимом `ampulse` через `keytool` из `JAVA_HOME` (нет — `D:\Android\jdk-21`) и
пишет `keystore.properties` в корень проекта. Файл ключа по умолчанию —
`E:\dev_personal\ampulse-scratch\keys\ampulse-release.jks` (другое место — `-Path`). Существующий ключ и
существующий `keystore.properties` скрипт не перезаписывает. В хранилище PKCS12 один пароль и на хранилище, и
на ключ.

**Где ключ берётся при сборке** (`app/build.gradle.kts`):

| Откуда | Что |
|---|---|
| `keystore.properties` в корне проекта (в `.gitignore`) | ключи `store` (путь к файлу), `storePassword`, `alias`, `keyPassword` |
| переменные окружения (CI) | `AMPULSE_KEYSTORE` (путь к файлу), `AMPULSE_KEYSTORE_PASSWORD`, `AMPULSE_KEY_ALIAS`, `AMPULSE_KEY_PASSWORD` |

Файл `keystore.properties` читается первым. **Без ключа выпускная сборка (`:app:assembleGithubRelease`) падает**
с понятным текстом — отладочной подписью выпуск не подписывается. Отладочная сборка ключа не требует.

**Секреты GitHub** (репозиторий → Settings → Secrets and variables → Actions → New repository secret):

| Секрет | Значение |
|---|---|
| `AMPULSE_KEYSTORE` | файл ключа в base64 одной строкой |
| `AMPULSE_KEYSTORE_PASSWORD` | пароль хранилища |
| `AMPULSE_KEY_ALIAS` | `ampulse` |
| `AMPULSE_KEY_PASSWORD` | пароль ключа (тот же, что у хранилища) |

Base64 одной строкой (в буфер обмена):

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes('E:\dev_personal\ampulse-scratch\keys\ampulse-release.jks')) | Set-Clipboard
```

Ключ видит только шаг сборки выпуска в workflow; задание, которое публикует Release, ключа не получает.

## Выпуск

Тег и CI запускаются только по слову владельца.

1. Поднять `appVersion` в `gradle.properties` (и версию в `docs/README.md`), закоммитить.
2. Прогнать проверку на ПК (раздел «Сборка и проверка на ПК» выше) — код выхода 0.
3. Написать заметки выпуска по-английски в `notes.md` (заголовки и списки Markdown; их покажет «Что нового» в
   приложении) и поставить тег с заметками на этот коммит:

   ```sh
   git tag -a vX.Y.Z --cleanup=verbatim -F notes.md
   ```

   `--cleanup=verbatim` нужен, чтобы git не вырезал строки с `#` (заголовки) как комментарии. Тег — `v` плюс
   `appVersion` (`v0.2.0`; пред-выпуск — `v0.3.0-beta.1`).
4. Отправить коммит и тег — **по команде владельца**: `git push origin main vX.Y.Z`.
5. На тег запускается workflow «Android»: проверяет, что тег = `v` + `appVersion` и что секреты ключа заданы
   (нет — падает с понятной причиной, ничего не публикуя); тесты и lint; подписанный APK
   `ampulse-X.Y.Z-android.apk`; `SHA256SUMS`; GitHub Release с этими двумя файлами и заметками из тега.
   Для `-alpha.N`, `-beta.N`, `-rc.N` Release помечается как пред-выпуск.
6. Проверить: в Release два файла, сумма сходится, APK встаёт на телефон поверх предыдущей версии
   (обновление изнутри приложения предложит его само).

## Сборка на GitHub по кнопке

Workflow `.github/workflows/android.yml` запускается **только по кнопке и на тег `v*`** — на `push` в ветку и
на `pull request` он не реагирует. По кнопке (Actions → «Android» → Run workflow, выбрать ветку) он гоняет
`:app:testGithubDebugUnitTest :app:lintGithubDebug :app:assembleGithubDebug` и кладёт отладочный APK в
артефакты прогона (`ampulse-debug-apk`, 14 дней). Новый запуск отменяет идущий, у заданий есть потолок
времени, Ubuntu и JDK 21 закреплены. Перед кнопкой те же команды прогоняются на ПК — проба на GitHub
дороже. Нажимает кнопку владелец или по его слову.
