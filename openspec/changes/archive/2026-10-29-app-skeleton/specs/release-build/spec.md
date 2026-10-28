# Spec Delta

## Purpose

Как собирается и выпускается Ampulse: версия, подпись ключом владельца, APK на GitHub по соглашению семьи.

## ADDED Requirements

### Requirement: Version
`versionName` SHALL быть `X.Y.Z` из одной строки `appVersion`; `versionCode` — `(X·10000 + Y·100 + Z)·100 + K`
(K = 99 у выпуска, N у пред-выпуска); номер сборки — число коммитов, мелко в «О программе».

#### Scenario: Version 0.2.0
- **WHEN** `appVersion=0.2.0`
- **THEN** `versionName` = 0.2.0, `versionCode` = 20099

### Requirement: Signed release
Выпуск SHALL подписываться одним ключом владельца, который не лежит в репозитории. Без ключа сборка выпуска MUST
завершаться ошибкой, а не отладочной подписью.

#### Scenario: No key in CI
- **WHEN** workflow запущен на теге, а секретов ключа нет
- **THEN** выпуск не публикуется, прогон падает с понятной причиной

### Requirement: GitHub build
Сборка на GitHub SHALL запускаться только по кнопке и на тег `v*` (без push и pull request), с подмодулями.
По кнопке — тесты, lint и отладочный APK; на тег — подписанный `ampulse-X.Y.Z-android.apk` и `SHA256SUMS` в GitHub
Release с заметками по-английски.

#### Scenario: Tag v0.2.0
- **WHEN** поставлен тег `v0.2.0` и секреты ключа заданы
- **THEN** в Release лежат `ampulse-0.2.0-android.apk` и `SHA256SUMS` со строкой для него
