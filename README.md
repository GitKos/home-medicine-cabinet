# Домашняя аптечка — Android-приложение

Нативный Android-проект (Kotlin + WebView + нативные уведомления `NotificationCompat` и `AlarmManager`), собранный на базе вашей обновлённой веб-версии «Домашней аптечки».

## Что входит в проект
- `app/src/main/assets/index.html` — ваше веб-приложение с учётом лекарств, инструкциями из Wikipedia/OpenFDA и расписанием приёма.
- `MainActivity.kt` и `AlarmReceiver.kt` — нативная обёртка с поддержкой `localStorage`, выбором фото из галереи, открытием внешних справочников (РЛС/Vidal/Wikipedia) и системными Android-уведомлениями.
- `.github/workflows/build-apk.yml` — готовый GitHub Actions workflow для автоматической сборки `.apk` при загрузке на GitHub.

## Как получить APK
1. **Через Android Studio**: откройте папку `home-medicine-cabinet-android` в Android Studio → дождитесь синхронизации Gradle → меню **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
2. **Через GitHub (без установки Android Studio)**: залейте этот проект в репозиторий на GitHub, откройте вкладку **Actions** и скачайте готовый `HomeMedicineCabinet-debug-apk`.
