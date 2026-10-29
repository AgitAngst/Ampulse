# Ampulse — политика конфиденциальности / Privacy policy

Действует с версии 0.9.0 (29.10.2026). English below.

## По-русски

Ampulse — помощник по приёму лекарств, уколам и давлению. Он устроен так, чтобы данные о здоровье не покидали телефон.

**Что хранится и где.** Лекарства, схемы, отметки приёмов, упаковки, замеры давления, места уколов, профили близких и
питомцев, близкие (имя и телефон), экстренная карта, режим дня — только на телефоне, в памяти приложения. У Ampulse нет
сервера и учётных записей. В облачную копию Google данные не попадают; при переносе на новый телефон средствами Android
они переезжают вместе с приложением.

**Что уходит с телефона — только по вашему действию или настройке:**

- **SMS близким**, которых вы добавили сами: о пропуске важного приёма и об открытой экстренной карте, если вы включили
  это у близкого. В сборке с GitHub SMS отправляет сам телефон; в сборках RuStore и Google Play открывается готовое
  сообщение, «Отправить» нажимаете вы. Текст SMS — название лекарства и время либо факт открытия карты.
- **QR-коды** (ваше расписание для близкого, экстренная карта) — показываются на экране; кто их сканирует, решаете вы.
- **Отчёт врачу (PDF)** — создаётся на телефоне; куда его отправить, выбираете вы в «Поделиться».

**Сеть.** Сборки RuStore и Google Play не обращаются в интернет вообще (разрешения на сеть в них нет). Сборка с GitHub
обращается к GitHub, чтобы узнать, нет ли новой версии (запрос без ваших данных; проверку можно выключить в
«Настройках»), и к GitHub при скачивании обновления; входящая в неё библиотека сканера кодов (Google ML Kit, модель на
телефоне) может отправлять Google обезличенную служебную статистику работы сканера.

**Распознавание.** Текст на фото рецепта и коды упаковок распознаются на телефоне. Голос распознаёт служба речи вашего
телефона: если язык для распознавания без сети не скачан, она может обращаться к серверам своего поставщика — по её
собственным правилам.

**Разрешения:** уведомления и точные будильники — напоминания; камера — сканер, фото рецепта и экрана тонометра;
микрофон — диктовка; «Устройства поблизости» (Bluetooth; на Android 10–11 — местоположение, только для поиска) —
тонометр; SMS (только сборка с GitHub) — SMS близким. Всё — по запросу и только для названного.

**Удаление.** «Удалить профиль», «Удалить близкого» и удаление лекарств стирают данные сразу; удаление приложения стирает
всё.

Вопросы — в [Issues](https://github.com/AgitAngst/Ampulse/issues) репозитория.

## In English

Ampulse helps with medicines, injections and blood pressure. It is built so that health data stays on the phone.

**What is stored and where.** Medicines, schedules, dose records, packs, blood pressure readings, injection sites,
profiles of family members and pets, contacts (name and phone number), the emergency card and the daily routine are
stored only on the phone, in the app's private storage. Ampulse has no server and no accounts. Nothing goes to Google
cloud backup; a device-to-device transfer made with Android tools moves the data together with the app.

**What leaves the phone — only by your action or setting:**

- **SMS to family members** you added yourself: about a missed important dose and about an opened emergency card, if you
  turned this on for that person. The GitHub build sends the SMS itself; in the RuStore and Google Play builds a ready
  message opens and you press Send. The text is the medicine name and time, or the fact that the card was opened.
- **QR codes** (your schedule for a family member, the emergency card) are shown on screen; you decide who scans them.
- **The doctor report (PDF)** is made on the phone; you choose where to send it with Share.

**Network.** The RuStore and Google Play builds do not use the internet at all (they have no network permission). The
GitHub build contacts GitHub to check for a newer version (no personal data in the request; the check can be turned off
in Settings) and to download an update; the barcode scanner library inside it (Google ML Kit, on-device model) may send
Google anonymous technical statistics about the scanner.

**Recognition.** Prescription photos and pack codes are recognised on the phone. Voice is recognised by your phone's
speech service: if the offline language is not downloaded, it may contact its provider's servers under its own terms.

**Permissions:** notifications and exact alarms — reminders; camera — scanner, prescription and monitor photos;
microphone — dictation; Nearby devices (Bluetooth; on Android 10–11 location, only for scanning) — blood pressure
monitor; SMS (GitHub build only) — messages to family. Each is asked for when needed and used only for that.

**Deletion.** Deleting a profile, a contact or a medicine erases that data at once; uninstalling the app erases
everything.

Questions — in the repository [Issues](https://github.com/AgitAngst/Ampulse/issues).
