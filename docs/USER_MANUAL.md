# Personal Android SMS Forwarder — User Operations & Deployment Manual

**Application Name:** SMS Forwarder  
**Package:** `com.smsforwarder.app`  
**Target Android Version:** Android 8.0 (API 26) through Android 15 (API 35)  
**Distribution Model:** Personal Sideloaded Release APK  
**Architecture:** Privacy-First, Zero-Telemetry, Android Keystore Encrypted  

---

## 1. System Overview & Prerequisites

The Personal Android SMS Forwarder is a hardened, battery-conscious Android application designed to forward incoming SMS text messages to modern webhooks, Telegram bots, native email inboxes, or native Meta WhatsApp Agents without relying on third-party cloud aggregators or unverified SDKs.

### Hardware & OS Requirements:
- **Operating System:** Android 8.0 Oreo (API level 26) or newer (tested up to Android 15 / API level 35).
- **Dual-SIM Support:** Multi-SIM devices running dual active SIM subscriptions are fully supported.
- **Hardware Keystore:** Requires Android Keystore hardware-backed encryption (StrongBox supported on hardware with a dedicated secure element).
- **RAM & Disk:** Lightweight footprint (< 15 MB install footprint, < 35 MB runtime RAM).

### Required Android System Permissions:
During initial setup, the application requests the following granular runtime permissions:
| Permission | Technical Name | Operational Justification |
| :--- | :--- | :--- |
| **Receive SMS** | `android.permission.RECEIVE_SMS` | Required to detect incoming SMS PDUs with highest priority broadcast reception. |
| **Phone State** | `android.permission.READ_PHONE_STATE` | Required to associate incoming SMS with specific SIM slots (SIM 1 vs SIM 2) and carrier tags. |
| **Notifications** | `android.permission.POST_NOTIFICATIONS` | (Android 13+) Required to present the mandatory ongoing foreground service indicator. |
| **Ignore Battery Optimizations** | `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Required to prevent OEM Doze mode from killing the background forwarder when idle. |

---

## 2. Step-by-Step Installation & Sideloading

Because this application is built for personal sovereignty and zero tracking, it is distributed as a self-contained release APK (`app-release.apk`) rather than through commercial app stores.

### 2.1 Preparing Your Android Device
1. On your Android device, navigate to **Settings** > **Security** (or **Apps** > **Special App Access**).
2. Tap **Install Unknown Apps**.
3. Select your preferred file manager, browser, or ADB bridge through which you will transfer the APK, and toggle **Allow from this source** to **ON**.

### 2.2 Sideloading the APK
#### Method A: ADB Command Line (Recommended for Technical Users)
Connect your device via USB with USB Debugging enabled, then execute from your host workstation:
```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

#### Method B: Direct File Transfer
1. Copy `app-release.apk` to your phone's internal storage or download it via local Wi-Fi.
2. Open your device's Files app, tap `app-release.apk`, and confirm the installation prompt.
3. Once installation completes, open **SMS Forwarder**.

### 2.3 Initial Permission Granting
When opening the app for the first time:
1. Tap **Grant Permissions** when prompted on the Dashboard.
2. Select **Allow** for SMS reception and Phone state access.
3. On Android 13+, confirm notification display permission.

---

## 3. OEM Battery Management & Background Whitelisting

Android manufacturers implement aggressive proprietary battery-killer tasks that terminate background services when the screen is turned off. To ensure 100% reliable SMS forwarding:

### 3.1 Standard Android Doze Whitelisting (All Devices)
1. Open **SMS Forwarder** and navigate to the **Settings** tab.
2. Locate the **Background Execution** card.
3. If the status indicates *Restricted*, tap the **Exempt** button.
4. An Android system dialog will appear: **"Stop optimizing battery usage?"** Tap **Allow**.

### 3.2 Manufacturer-Specific Autostart Configurations
Different OEMs require additional proprietary toggles:

#### Samsung (One UI)
1. Go to **Settings** > **Apps** > **SMS Forwarder** > **Battery**.
2. Select **Unrestricted** (do NOT choose *Optimized* or *Restricted*).
3. Go to **Settings** > **Battery** > **Background usage limits**:
   - Ensure SMS Forwarder is NOT listed under *Sleeping apps* or *Deep sleeping apps*.
   - Add SMS Forwarder to **Never sleeping apps**.

#### Xiaomi / Redmi / POCO (MIUI / HyperOS)
1. Long-press the **SMS Forwarder** app icon > tap **App Info** (`i`).
2. Toggle **Autostart** to **ON**.
3. Tap **Battery Saver** > change from *MIUI Battery Saver* to **No Restrictions**.
4. Open the Recent Apps tray, long-press the SMS Forwarder card, and tap the **Lock Icon** to lock it in RAM.

#### Huawei / Honor (EMUI / MagicOS)
1. Go to **Settings** > **Battery** > **App Launch**.
2. Find **SMS Forwarder** and toggle from *Manage automatically* to **Manage manually**.
3. In the pop-up, ensure all three switches are **ON**:
   - *Auto-launch*
   - *Secondary launch*
   - *Run in background*

#### OnePlus / Oppo / Realme (OxygenOS / ColorOS)
1. Go to **Settings** > **Apps** > **App management** > **SMS Forwarder** > **Battery usage**.
2. Turn **ON** both **Allow background activity** and **Allow auto-launch**.
3. Go to **Battery** > **More settings** > **Optimize battery use** > find SMS Forwarder > select **Don't optimize**.

---

## 4. Destination Protocol Configuration Guide

SMS Forwarder supports four independent, production-grade outbound destination protocols. Credentials are encrypted into hardware Keystore storage upon saving.

### 4.1 Native Meta WhatsApp Agent Platform (API v1)
*Recommended for direct, personal WhatsApp alerts without running third-party bots.*

#### Credentials Required:
Only ONE credential is required: the **WhatsApp Agent API Key**.
- Obtained from: WhatsApp > **Settings** > **Agents** > (Select Agent) > **Chat info** > **API Key**.

#### Setup Steps:
1. Open SMS Forwarder > navigate to **Settings**.
2. In the **WhatsApp Agent Platform (API v1)** card:
   - Paste your **WhatsApp Agent API Key**.
3. On your phone, open the official **WhatsApp** app:
   - Open the chat with your configured Agent.
   - Send any test text message (e.g., *"Hello"* or *"Link"*).
4. Return to **SMS Forwarder** and tap **Verify & Link**.
   - The app securely calls `GET /agent/v1/updates?limit=50&offset=0`, reads the inbound update, and binds your participant ID (`user:<id>`).
   - A green confirmation badge will appear: `Linked Recipient: user:123456789`.
5. Tap **Send Test SMS** to verify end-to-end delivery. Exactly one test message will be sent to your WhatsApp account.
6. Create a Rule under the **Rules** tab, select **WHATSAPP_AGENT**, and tap **Save Forwarding Rule**.

---

### 4.2 Telegram Bot API
*Recommended for group notifications or custom Telegram channels.*

#### Credentials Required:
- **Bot Token:** Created via [@BotFather](https://t.me/BotFather) on Telegram (e.g., `123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ12345678`).
- **Destination Chat ID:** Your personal numerical user ID, group ID, or supergroup ID (e.g., `-1001234567890` or `987654321`).

#### Setup Steps:
1. Navigate to the **Rules** tab > tap the **+** button.
2. Name your rule (e.g., *"Bank OTPs to Telegram"*).
3. Select Destination Type: **TELEGRAM**.
4. Enter your **Destination Chat ID**.
5. Paste your **Telegram Bot API Token** into the secure field.
6. Configure any sender or keyword filters, then tap **Save Forwarding Rule**.
7. The bot token is immediately vaulted in Android Keystore AES-256-GCM and masked from all system logs.

---

### 4.3 Custom Webhook Endpoint (HTTPS)
*Recommended for self-hosted home servers, automation pipelines, and Cloudflare Workers.*

#### Security Requirement:
- Cleartext `http://` URLs are strictly rejected. The target endpoint **must use HTTPS**.
- Encrypted with TLS 1.2 or TLS 1.3 modern cipher suites.

#### Setup Steps:
1. Navigate to the **Rules** tab > tap **+**.
2. Select Destination Type: **WEBHOOK**.
3. In **Webhook URL**, enter your endpoint (e.g., `https://api.yourdomain.com/sms`).
4. (Optional) In **Authorization Header**, enter your Bearer token or basic auth secret.
5. (Optional) In **Custom JSON Headers**, supply any specific header mapping:
   ```json
   {"X-Forwarder-Secret": "my_super_secret_token"}
   ```
6. Tap **Save Forwarding Rule**.

---

### 4.4 Native SMTP Email Dispatch
*Recommended for email archives or forwarding to work addresses.*

#### Setup Steps:
1. Navigate to the **Rules** tab > tap **+**.
2. Select Destination Type: **EMAIL**.
3. Fill in the mail server credentials:
   - **Recipient Email Address:** e.g., `alerts@yourdomain.com`
   - **SMTP Host:** e.g., `smtp.gmail.com` or `mail.privateemail.com`
   - **SMTP Port:** `465` (SSL/TLS direct) or `587` (STARTTLS)
   - **SMTP Username:** Full username or email address
   - **SMTP Password:** Account password or generated App Password
   - **From Address:** Sender address header
   - **Use STARTTLS:** Toggle ON if using port 587; toggle OFF for port 465.
4. Tap **Save Forwarding Rule**.

---

## 5. Privacy & Security Operations

### 5.1 Zero-History Mode (Ephemeral Execution)
For users who receive sensitive financial authentication codes, OTPs, or passwords and do not want messages stored on the device:
1. Go to **Settings** > **Data Retention & Privacy**.
2. Toggle **Zero-History Mode (Ephemeral)** to **ON**.
3. **Behavior:** The incoming SMS is ingested into SQLite only long enough to confirm transmission. As soon as a dispatcher confirms HTTP 200/204, the SQLite message body is immediately overwritten with `[PURGED]` and raw data is zeroed out in memory.

### 5.2 Configurable Data Retention Policy
If Ephemeral Mode is disabled, you can retain local history for auditing:
- Supported retention periods: **1 Day**, **7 Days**, **14 Days**, **30 Days**.
- The `RetentionPruneWorker` runs daily in the background to automatically delete expired messages and associated delivery tasks.

### 5.3 Emergency Panic Button / Factory Wipe
If your device is lost, stolen, or compromised:
1. Go to **Settings** > scroll to the bottom.
2. Tap the red button: **Factory Reset / Wipe All Data**.
3. Confirm the dialog: **Wipe Everything**.
4. **Immediate Impact:**
   - All SMS records in the Room database are irrevocably deleted.
   - All forwarding rules and delivery queues are purged.
   - The hardware Keystore vault (`EncryptedSharedPreferences`) is completely cleared.
   - The background forwarding service is immediately halted.

---

## 6. Offline Queue & Troubleshooting

### 6.1 State Machine Lifecycle
When an SMS arrives:
```
[SMS Arrived] ──► Deduplication Check (10s Window)
                       │
                       ▼
                 [PENDING]
                       │
                       ▼
                 [IN_PROGRESS]
                 /     │     \
     (Success)  /      │      \ (Rate Limit 429 / 503 code 131016)
               ▼       │       ▼
          [SUCCESS]    │   [FAILED_RETRYABLE] (Exponential Backoff with Jitter)
                       │       │
                       │       ▼ (Exhausted 5 attempts or fatal error)
                       └──► [FAILED_EXHAUSTED]
```

### 6.2 Dead-Letter Recovery & Manual Retry
If the phone loses Wi-Fi and cellular data:
- Tasks fail transiently and enter `FAILED_RETRYABLE`.
- WorkManager schedules retries using exponential backoff with full jitter (between 30s and 30m).
- When internet connectivity is restored, the `NetworkConnectivityMonitor` automatically detects the network change (with a 1-second debounce) and immediately flushes all pending and retryable tasks.
- If a task reaches 5 failed attempts, it transitions to `FAILED_EXHAUSTED`.
- To retry failed tasks: Open the **History** tab > tap the **Retry All Failed** icon at the top right.

### 6.3 Common Troubleshooting Scenarios
| Symptom | Likely Cause | Remediation |
| :--- | :--- | :--- |
| **No SMS forwarded when phone is idle** | OEM battery optimization killed the app service | Follow Section 3 to exempt the app from battery optimization and enable Autostart. |
| **WhatsApp returns HTTP 400 (code 131009)** | Destination participant ID is malformed | In Settings, tap **Verify & Link** to bind the required `user:<id>` format. |
| **WhatsApp returns HTTP 403 (code 131005)** | Recipient is not the agent's creator | Send a message from the creator's WhatsApp account to the agent, then re-verify. |
| **Telegram messages fail with HTTP 400** | Chat ID is missing or incorrect | Start a chat with your bot first, send `/start`, and use `@userinfobot` to retrieve your numerical Chat ID. |
| **SMTP fails with handshake error** | Port and TLS setting mismatch | Use Port 465 with STARTTLS OFF, or Port 587 with STARTTLS ON. For Gmail, use an **App Password**. |
| **Webhook fails with TLS error** | Webhook server uses obsolete TLS or HTTP | Ensure your server has a valid SSL certificate supporting TLS 1.2 or TLS 1.3. |
