# store-builds Specification

## Purpose
Ampulse ставится не только APK из GitHub, но и из RuStore и Google Play — с правилами каждого магазина и без потери
главного: напоминания и SMS близким работают в каждой сборке.

## Requirements

### Requirement: Store flavors
Сборки `rustore` и `play` SHALL собираться из того же кода, без своей проверки обновлений и без разрешения на сеть;
`play` MUST выпускаться AAB без деления по языкам.

#### Scenario: No network in store builds
- **WHEN** собрана сборка `rustore` или `play`
- **THEN** в её манифесте нет `INTERNET` и `REQUEST_INSTALL_PACKAGES`

### Requirement: SMS without SEND_SMS
В сборках без `SEND_SMS` SMS близкому SHALL предлагаться уведомлением с готовым сообщением; касание MUST открывать
приложение сообщений с номером и текстом.

#### Scenario: Missed dose in Play build
- **WHEN** важный приём не отмечен в сборке `play`
- **THEN** приходит уведомление «Отправьте SMS: Анна», касание открывает готовое сообщение
