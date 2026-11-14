<div align="center">

# 📲 SMS Forwarder for Android

**High-reliability, privacy-first Android SMS forwarding engine with hardware-backed encryption, multi-provider dispatch, dual-SIM routing, and dynamic template rendering.**

[![Android Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20(API%2026%2B)-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0.21-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Jetpack Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20M3-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)](https://developer.android.com/jetpack/compose)
[![Security](https://img.shields.io/badge/Encryption-Keystore%20AES--256--GCM-critical?style=for-the-badge&logo=security&logoColor=white)](https://developer.android.com/training/articles/keystore)
[![Latest Release](https://img.shields.io/github/v/release/hanzlaahmadcheema/sms-forwarder?style=for-the-badge&color=blue)](https://github.com/hanzlaahmadcheema/sms-forwarder/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=for-the-badge)](LICENSE)

[Download APK](https://github.com/hanzlaahmadcheema/sms-forwarder/releases/latest) • [Key Features](#-key-features) • [Quick Setup](#-quick-setup--batch-json-import) • [Template Engine](#-dynamic-template-engine) • [Architecture](#-system-architecture) • [Building](#-build-from-source)

</div>

---

## 📖 Overview

**SMS Forwarder** is a resilient, autonomous Android utility engineered for individuals and teams requiring instantaneous, zero-loss routing of cellular SMS traffic to webhooks, messaging platforms, and email inboxes. Built following clean architectural standards, the application runs 100% locally on-device without third-party intermediate analytics, tracking servers, or cloud dependencies.

All secrets (API keys, bot tokens, auth headers, and SMTP passwords) are strictly protected inside the hardware-backed **Android Keystore** using **AES-256-GCM** authenticated encryption.

---

## ✨ Key Features

### 🚀 Multi-Channel Dispatch
Deliver incoming SMS messages simultaneously across multiple target destinations:
* **Telegram Bot API:** Instant Markdown/HTML alerts delivered to personal chats or group channels.
* **WhatsApp Agent API:** Direct integration with WhatsApp business agents and automated pipelines.
* **HTTP / REST Webhooks:** Custom JSON payloads with customizable authorization headers, TLS 1.2/1.3, and custom HTTP headers (compatible with Discord, Slack, Zapier, n8n, Make, or custom REST servers).
* **Native SSL/TLS SMTP (Email):** RFC-compliant direct SSLSocket mail dispatch (no bulky JavaMail dependency).

### 📶 Granular Dual-SIM Routing
Filter and dispatch incoming messages based on the physical cellular hardware source:
* Route **Any SIM** for global forwarding.
* Pin rules specifically to **SIM 1** (e.g. personal line) or **SIM 2** (e.g. business/banking line).
* Seamless integration with Android `SubscriptionManager` slot index resolution.

### 📇 Address Book & Dynamic Token Formatting
Transform raw, cryptic incoming texts into polished alerts:
* **Contact Name Resolution:** Resolves originating phone numbers against device contacts (`ContactsContract.PhoneLookup`).
* **Timestamp & Date Tokens:** Automatically inject localized dates (`11 November 2026`), day names (`Wednesday`), and military or standard timestamps (`20:27:18`).
* **Aesthetic Border Presets:** Built-in Unicode border styling presets (Double-Box, Rounded Modern Card, Security Badge, Minimalist Divider).

### ⚡ Batch Provisioning via Single-Payload JSON
Import complete, multi-channel forwarding topologies and application configurations in a single step via **Rules > ⚡ Paste JSON**. Supports nested credentials encryption and instant activation.

### 🛡️ Ironclad On-Device Security & Privacy
* **Zero Cloud Dependency:** Runs entirely on-device; messages are processed in-memory and stored only in a local SQLite Room database.
* **Hardware-Backed Cryptography:** Secret keys are generated and stored in Android Keystore; ciphertext is encrypted using AES-256-GCM with unique 12-byte IVs.
* **Configurable Data Retention:** Automated pruning worker sweeps records older than your chosen threshold (e.g. 1 to 90 days), with optional **Ephemeral Mode** (zero disk logging).

### 🔋 Rock-Solid Background Reliability
* **Foreground Service:** Persistent foreground service with low memory footprint and customizable notification status.
* **WorkManager Retry Queue:** Exponential backoff retry queue with network connectivity constraints ensures zero lost messages during network drops.
* **Boot Auto-Start:** Automatically rebinds service listeners upon device restart (`BOOT_COMPLETED`).

---

## 🏗️ System Architecture

```
                       ┌─────────────────────────┐
                       │   Incoming Cellular SMS │
                       └────────────┬────────────┘
                                    │
                                    ▼
                       ┌─────────────────────────┐
                       │       SmsReceiver       │
                       └────────────┬────────────┘
                                    │ (Extract SIM slot, sender, body)
                                    ▼
                       ┌─────────────────────────┐
                       │   ReceiveSmsUseCase     │
                       └─────┬─────────────┬─────┘
                             │             │
        (Resolve Contact)    │             │ (Deduplication Check)
                             ▼             ▼
                     [ ContactHelper ]  [ Room SQLite DB ]
                             │             │
                             └──────┬──────┘
                                    ▼
                       ┌─────────────────────────┐
                       │    FilterSmsUseCase     │
                       └────────────┬────────────┘
                                    │ (Match SIM, Sender, Keywords)
                                    ▼
                       ┌─────────────────────────┐
                       │ EnqueueForwardingUseCase│
                       └────────────┬────────────┘
                                    │
                                    ▼
                       ┌─────────────────────────┐
                       │   DeliveryQueueManager  │
                       └────────────┬────────────┘
                                    │
      ┌────────────────┬────────────┴───────────┬────────────────┐
      ▼                ▼                        ▼                ▼
┌───────────┐    ┌───────────┐            ┌───────────┐    ┌───────────┐
│  Telegram │    │ WhatsApp  │            │  Webhook  │    │   Email   │
│ Dispatcher│    │ Dispatcher│            │ Dispatcher│    │ Dispatcher│
└─────┬─────┘    └─────┬─────┘            └─────┬─────┘    └─────┬─────┘
      │                │                        │                │
      └────────────────┴────────────┬───────────┴────────────────┘
                                    ▼
                       ┌─────────────────────────┐
                       │ Android Keystore Vault  │
                       │     (AES-256-GCM)       │
                       └─────────────────────────┘
```

---

## ⚡ Quick Setup & Batch JSON Import

The fastest way to configure your forwarding rules is by importing a JSON bundle directly in the app (**Rules Screen ➔ ⚡ Paste JSON**):

### Example: Dual-SIM Relay Setup (SIM 2 Only)

```json
{
  "settings": {
    "defaultTemplate": "{senderNumber}\n{contactName}\n\n{body}\n\n{date}\n{day}\n{time}",
    "retentionDays": 7,
    "deduplicationWindowSeconds": 60,
    "networkTimeoutSeconds": 30
  },
  "providers": [
    {
      "name": "Telegram Relay",
      "type": "WEBHOOK",
      "endpoint": "https://sms-relay.your-worker.workers.dev/sms-relay",
      "sim_slot": 1,
      "template": "{senderNumber}\n{contactName}\n\n{body}\n\n{date}\n{day}\n{time}"
    },
    {
      "name": "WhatsApp Agent",
      "type": "WHATSAPP_AGENT",
      "endpoint": "user:1234567890",
      "api_key": "wa_live_secret_key_example",
      "sim_slot": 1,
      "template": "{senderNumber}\n{contactName}\n\n{body}\n\n{date}\n{day}\n{time}"
    }
  ]
}
```

> **Note on SIM Slots:** Android uses `0` for SIM 1, `1` for SIM 2, and `-1` for Any SIM.

---

## 📝 Dynamic Template Engine

Custom templates allow dynamic variable injection into forwarded messages.

### Available Tokens

| Token | Description | Sample Output |
| :--- | :--- | :--- |
| `{senderNumber}` | Originating phone number or sender ID | `+1 (555) 234-5678` |
| `{contactName}` | Resolved contact name from device contacts | `John Doe` (or fallback) |
| `{body}` | Full text content of the SMS message | `Your OTP code is 492810` |
| `{date}` | Localized date (`dd MMMM yyyy`) | `14 November 2026` |
| `{day}` | Day of the week (`EEEE`) | `Saturday` |
| `{time}` | 24-hour timestamp (`HH:mm:ss`) | `14:32:05` |
| `{simSlot}` | Display label of the SIM slot receiving SMS | `SIM 1` / `SIM 2` |

### Styling Presets

#### 1. Double-Box Border
```text
╔══════════════════════════════════════╗
║ 📩 SMS ALERT: {contactName}          
║ From: {senderNumber}                 
╠══════════════════════════════════════╣
║ {body}                               
╠══════════════════════════════════════╣
║ 📅 {date} ({day}) ⏰ {time}          
╚══════════════════════════════════════╝
```

#### 2. Modern Rounded Card
```text
╭──────────────────────────────────────╮
│ From: {senderNumber} ({contactName}) 
│                                      
│ {body}                               
│                                      
│ 🕒 {date} • {time} [{simSlot}]       
╰──────────────────────────────────────╯
```

#### 3. Security Alert Badge
```text
┏━━ 🚨 VERIFICATION CODE / OTP ━━━━━━━┓
┃ From: {senderNumber}                
┃ Received: {date} {time}             
┣━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┫
┃ {body}                              
┗━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━┛
```

---

## 🛠️ Build from Source

### Prerequisites
* JDK 17 or higher
* Android SDK 35 (Platform 35, Build Tools 35.0.0)
* Android 8.0+ (API 26+) test device or emulator

### Compilation Commands

```bash
# Clone the repository
git clone https://github.com/hanzlaahmadcheema/sms-forwarder.git
cd sms-forwarder

# Run the comprehensive unit test suite
./gradlew testReleaseUnitTest

# Assemble the signed release APK
./gradlew assembleRelease
```

The compiled release binary is generated at:
```
app/build/outputs/apk/release/app-release.apk
```

---

## 📱 Permissions & Runtime Requirements

| Permission | Purpose |
| :--- | :--- |
| `RECEIVE_SMS` | Intercept incoming cellular SMS messages in real-time |
| `READ_SMS` | Parse multipart SMS messages and extract metadata |
| `READ_CONTACTS` | Query `ContactsContract.PhoneLookup` to resolve names |
| `INTERNET` | Dispatch payloads to webhooks, Telegram, WhatsApp, and SMTP |
| `ACCESS_NETWORK_STATE` | Ensure connectivity before triggering network dispatches |
| `FOREGROUND_SERVICE` | Guarantee continuous listener uptime in background |
| `RECEIVE_BOOT_COMPLETED` | Automatically resume forwarding service on system reboot |
| `POST_NOTIFICATIONS` | Foreground service persistent notification on Android 13+ |

> **Battery Optimization Tip:** For 24/7 continuous forwarding on Samsung (OneUI), Xiaomi (MIUI/HyperOS), and Huawei devices, whitelist the application under **Settings > Battery > Battery Optimization > Unrestricted**.

---

## 📄 License

This project is licensed under the [MIT License](LICENSE).

---

<div align="center">
Developed and maintained with precision by <b><a href="https://github.com/hanzlaahmadcheema">Hanzla Ahmad</a></b>.
</div>
