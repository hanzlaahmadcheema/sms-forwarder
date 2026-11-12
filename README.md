# SMS Forwarder for Android

Android application for forwarding SMS messages to Webhooks, Telegram, WhatsApp, and Email.

### Features
- Multi-provider forwarding: Webhooks, Telegram, WhatsApp, Email
- Dual SIM routing: All SIMs, SIM 1, or SIM 2
- Dynamic template formatting: `{senderNumber}`, `{contactName}`, `{body}`, `{date}`, `{day}`, `{time}`, `{simSlot}`
- Local SQLite database with Room
- Android Keystore AES-256-GCM encryption for credentials
- Foreground service and WorkManager retry mechanism

### Build
```bash
./gradlew testReleaseUnitTest
./gradlew assembleRelease
```
Compiled APK path: `app/build/outputs/apk/release/app-release.apk`
