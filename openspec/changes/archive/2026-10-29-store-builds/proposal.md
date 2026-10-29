# Proposal

## Why

Этап 0.9 (SPEC.md): сборки для RuStore и Google Play (решение владельца 29.10.2026: APK из GitHub, позже RuStore и
Google Play). Правила — anvil/docs/ANDROID.md, §2.

## What Changes

- Сборки `rustore` (APK) и `play` (AAB без деления по языкам): обновляет магазин, своей проверки обновлений нет, сети нет.
- SMS близким в сборках магазинов — уведомление с готовым сообщением, «Отправить» нажимает человек (`SEND_SMS` только в
  `github`).
- `play` — без напоминания на весь экран (запасной путь — уведомление с повтором).
- CI: кнопка проверяет, что сборки магазинов собираются; с галочкой — подписанные RuStore APK и Play AAB артефактом.
- Документы: как публиковать (`docs/dev/stores.md`), политика конфиденциальности (`docs/privacy.md`, ru+en).
- Версия 0.9.0.

## Capabilities

### New Capabilities
- `store-builds`: сборки для магазинов и их отличия.

### Modified Capabilities

## Impact

- `app/build.gradle.kts`, `src/stores/`, `src/rustore/`, `src/play/`, `reminders/SmsOut.kt`, `.github/workflows/android.yml`.
