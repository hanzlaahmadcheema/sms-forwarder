# Phase 1: Security Threat Model & Privacy Governance Specification

**Project:** Personal Android SMS Forwarder  
**Document ID:** SEC-PH1-SPEC-01  
**Author:** Auditor (`agent-155a4006-d652-4bed-813f-862abcd83fb7`)  
**Phase:** Phase 1 — Requirements Analysis & Technical Feasibility  
**Target Platform:** Android 8.0 (API 26) through Android 15 (API 35)  
**Classification:** Restricted / Engineering & Security Baseline  

---

## 1. Executive Summary & Security Philosophy

The **Personal Android SMS Forwarder** processes high-risk telecommunications data, specifically SMS messages containing One-Time Passwords (OTPs), multi-factor authentication (2FA) tokens, banking transaction confirmations, and private correspondence. In addition, the application stores high-value outgoing credentials, including Telegram Bot API tokens, SMTP mail passwords, and Webhook bearer authentication tokens.

Because this is a personal, sideloaded Android utility operating continuously in the background, the security architecture must adhere to **Defense-in-Depth** and the **Principle of Least Privilege**, with a baseline posture of **Zero-Telemetry, Zero-Leakage, and Zero-Trust Local Persistence**.

### Core Mandatory Security Invariants:
1. **Never Store Plaintext Credentials:** All API tokens, SMTP passwords, and secret headers must be hardware-bound and encrypted via Android Keystore (`MasterKey` with AES-256-GCM).
2. **Never Log Sensitive Payloads:** Standard Android Logcat output must strictly mask SMS message bodies, sender phone numbers, authorization headers, and tokens under all build variants.
3. **Zero Third-Party Telemetry:** Absolutely zero external analytics SDKs, crash reporters (Firebase Crashlytics, Sentry, AppCenter), or tracking libraries are permitted in the build dependency tree.
4. **Isolated Storage Architecture:** Cryptographic secrets and operational state/metadata must be strictly separated. Plain text databases must never hold credentials, and message content retention in local SQLite/Room must obey strict user-configurable lifecycle pruning.
5. **Transport Security Invariant:** Outbound transmissions (HTTPS Webhooks, Telegram API, SMTP over TLS) must enforce TLS 1.2+ minimum, disable cleartext HTTP system-wide, and validate X.509 certificate trust chains without bypass flags.
6. **Air-Gapped Local Backups:** Android OS cloud backups (`android:allowBackup="false"`) must be disabled to prevent accidental exfiltration of device encryption keys or cached messages to Google Drive / OEM cloud storage.

---

## 2. System Assets & Data Sensitivity Classification

| Asset Category | Specific Data Elements | Sensitivity Classification | Protection Requirement |
| :--- | :--- | :--- | :--- |
| **Inbound SMS Data** | OTP/2FA codes, bank notifications, sender phone numbers, raw message body | **CRITICAL** | Ephemeral memory handling; masked in UI logs; optional local DB storage with strict retention expiry; encrypted during transmission. |
| **Outbound Credentials** | Telegram Bot Token, SMTP username/password, Webhook Bearer tokens / Basic Auth credentials | **CRITICAL** | Android Keystore hardware-backed encryption (`MasterKey` + AES-256-GCM via `EncryptedSharedPreferences`); never serialized in Room DB or Git. |
| **Device & Network Metadata** | SIM Slot index, Subscription ID, Carrier name, device IP, battery status | **LOW-MEDIUM** | Non-PII metadata permitted in Room DB operational queue; excluded from public logs. |
| **Rule & Configuration State** | Filter rules (regex, keywords, sender white/blacklists), destination endpoints | **MEDIUM** | Stored in Room DB or unencrypted DataStore; secret references point to encrypted vault keys rather than inline strings. |
| **Operational Delivery Logs** | Timestamp, destination type, HTTP response code, delivery status, retry count | **LOW** | Local Room DB; automatically pruned after retention threshold (e.g. 7 days); no message body in error traces. |

---

## 3. Threat Model & Attack Vectors (STRIDE Analysis)

```
       +---------------------------------------------------------------------------------+
       |                              Android Device Boundary                            |
       |                                                                                 |
       |  +---------------------+        Intent          +----------------------------+  |
       |  |  Cellular Baseband  | ---------------------> | Manifest BroadcastReceiver |  |
       |  |     Subsystem       |  android.provider.     | (priority 999, protected   |  |
       |  +---------------------+  Telephony.            |  by BROADCAST_SMS perm)    |  |
       |                           SMS_RECEIVED          +--------------+-------------+  |
       |                                                                |                |
       |                                                                v                |
       |                                                 +----------------------------+  |
       |                                                 | Coroutine Engine / Pipeline|  |
       |                                                 | (Masking, Rule Matching)   |  |
       |                                                 +-------+------------+-------+  |
       |                                                         |            |          |
       |               +-----------------------------------------+            |          |
       |               v                                                      v          |
       |  +------------------------+                             +--------------------+  |
       |  | Room DB (Encrypted or  |                             | Android Keystore   |  |
       |  | Metadata / Auto-Pruned)|                             | MasterKey + AES-256|  |
       |  +------------------------+                             +---------+----------+  |
       |                                                                   |             |
       |                                                                   v             |
       |                                                         +--------------------+  |
       |                                                         | EncryptedSharedPref|  |
       |                                                         | (Vault Credentials)|  |
       |                                                         +---------+----------+  |
       |                                                                   |             |
       |  +----------------------------------------------------------------+             |
       |  |                                                                              |
       +--|------------------------------------------------------------------------------+
          | (TLS 1.2 / TLS 1.3 Strict HTTPS / SMTPS)
          v
   +---------------------------------------------------------------------+
   |                       External Network Services                     |
   |                                                                     |
   |  [ Telegram Bot API ]        [ Custom Webhook ]     [ SMTP Server ] |
   +---------------------------------------------------------------------+
```

### 3.1 Unintended Exposure of SMS Contents (OTP, 2FA, PII)
- **Threat Vector 1: Logcat Snooping.** Other installed applications or malicious ADB tools reading device logs (`logcat`). On Android 4.1+, third-party apps cannot read each other's logs, but USB debugging (ADB), bug reports, or compromised OEM logging agents can capture log buffers.
  - *Mitigation:* Zero Logcat dumping of raw SMS bodies. All log statements must pass through an audited `RedactingTree` (see Section 5.2).
- **Threat Vector 2: Notification Snooping.** Ongoing status notifications or delivery alerts exposing OTP content on the lock screen.
  - *Mitigation:* Background service notifications must show generic operational status (e.g. *"SMS Forwarder Active — 3 messages processed"*), never displaying raw SMS text or sender phone numbers. Notification channel visibility set to `VISIBILITY_SECRET` or `VISIBILITY_PRIVATE`.
- **Threat Vector 3: Memory Dump / Process Retention.** SMS strings lingering indefinitely in JVM garbage collector heap memory.
  - *Mitigation:* Message parsing scopes must be localized. Never store raw message strings in global static singletons.

### 3.2 Credential Leakage Vectors
- **Threat Vector 1: Accidental Git Commit.** Developer checks in testing Telegram bot tokens, Webhook URLs with secret query parameters, or personal SMTP credentials.
  - *Mitigation:* All sensitive configs must be entered dynamically via the UI in production builds. Build scripts must never embed default production secrets. `.gitignore` must strictly exclude any local properties, keystore files, or test fixtures.
- **Threat Vector 2: Plaintext Room Database Persistence.** Storing destination configurations with inline tokens in unencrypted Room SQLite tables (`forwarding_rules.telegram_bot_token`).
  - *Mitigation:* Architectural storage separation (Section 4.4). Room DB tables reference a `secret_key_alias`. The actual secret is stored exclusively in `EncryptedSharedPreferences`.
- **Threat Vector 3: Uncaught Crash Exception Traces.** Network exceptions (e.g., OkHttp `HttpException: 401 Unauthorized for https://api.telegram.org/bot<TOKEN>/sendMessage`) printing the URL containing the token into logs or crash dialogs.
  - *Mitigation:* Custom OkHttp interceptor that strips credentials and token paths from exception messages and logging events.

### 3.3 Man-in-the-Middle (MitM) & Network Interception Vectors
- **Threat Vector 1: Plaintext HTTP Downgrade.** Misconfigured custom Webhooks using `http://` instead of `https://`.
  - *Mitigation:* Android Network Security Configuration must set `cleartextTrafficPermitted="false"`. The UI rule creator must reject non-HTTPS URLs for Webhook destinations.
- **Threat Vector 2: Untrusted Certificates / TLS Proxy Interception.** Rogue CA certificates installed in the device user trust store attempting to inspect outgoing Webhooks or Telegram payloads.
  - *Mitigation:* Strict trust configuration targeting only system CAs (`<certificates src="system" />`). User CAs must not be trusted unless explicitly enabled in custom debug builds.
- **Threat Vector 3: SMTP Plaintext Authentication.** SMTP client transmitting credentials via unencrypted TCP port 25 without STARTTLS.
  - *Mitigation:* Enforce port 465 (implicit SSL/TLS) or port 587 (explicit STARTTLS). Forbid plaintext authentication on unencrypted sockets. Reject server certificates that fail hostname verification.

### 3.4 Insecure Local Storage, Backups, and Data Export
- **Threat Vector 1: Android Cloud Backup Exfiltration.** Android Auto-Backup uploading SQLite databases or shared preferences to Google Cloud Drive unencrypted.
  - *Mitigation:* Set `android:allowBackup="false"` and `android:fullBackupContent="false"` in `AndroidManifest.xml`.
- **Threat Vector 2: Unencrypted Configuration Export.** User exports forwarding rules to share or backup, generating a plaintext JSON file on external storage containing all API tokens.
  - *Mitigation:* Configuration exports must sanitize/strip all credentials, exporting rule topologies only. If full export is required, it must require user password-based encryption (PBKDF2/AES-GCM-256) before writing to disk.

### 3.5 Inter-Process Communication (IPC) & Intent Hijacking / Spoofing
- **Threat Vector 1: Spoofed SMS Broadcast.** A malicious application on the device fires a fake `SMS_RECEIVED` intent with forged sender and OTP payloads to trigger unauthorized forwarding.
  - *Mitigation:* The BroadcastReceiver declaration must require the system-level permission `android.permission.BROADCAST_SMS`. Third-party applications lack this permission and cannot send intents to the receiver.
- **Threat Vector 2: Exported Components.** Unintentionally exposing background services or internal activity components to external apps.
  - *Mitigation:* Explicitly set `android:exported="false"` on all Services, ContentProviders, and internal BroadcastReceivers in `AndroidManifest.xml`.

---

## 4. Cryptographic Standards for Sensitive Configuration

```
 +---------------------------------------------------------------------------------+
 |                           Android Keystore Subsystem                            |
 |                                                                                 |
 |  +---------------------------------------------------------------------------+  |
 |  | TEE (Trusted Execution Environment) / StrongBox Keymaster                 |  |
 |  |                                                                           |  |
 |  |  MasterKey Alias: "_sms_forwarder_master_key_"                            |  |
 |  |  Algorithm: AES-256-GCM                                                  |  |
 |  |  Purposes: ENCRYPT | DECRYPT                                              |  |
 |  |  Block Mode: GCM | Padding: NoPadding                                    |  |
 |  |  Key Size: 256 bits                                                       |  |
 |  +---------------------------------------------------------------------------+  |
 +---------------------------------------+-----------------------------------------+
                                         | Encrypts Keys & Values
                                         v
 +---------------------------------------------------------------------------------+
 |                    Jetpack Security: EncryptedSharedPreferences                 |
 |                                                                                 |
 |   File: "secure_forwarder_vault.xml" (MODE_PRIVATE)                             |
 |   PrefKeyEncryptionScheme: AES256_SIV                                           |
 |   PrefValueEncryptionScheme: AES256_GCM                                         |
 |                                                                                 |
 |   Vault Items:                                                                  |
 |   - "rule_{id}_telegram_token"  -> [Encrypted Ciphertext + Auth Tag]             |
 |   - "rule_{id}_smtp_password"   -> [Encrypted Ciphertext + Auth Tag]             |
 |   - "rule_{id}_webhook_auth"    -> [Encrypted Ciphertext + Auth Tag]             |
 +---------------------------------------------------------------------------------+
```

### 4.1 Android Keystore Architecture & MasterKey Specification
All sensitive secrets (passwords, tokens, custom authorization headers) must be encrypted via Android Jetpack Security (`androidx.security:security-crypto:1.1.0-alpha06` or latest stable).

#### Cryptographic Parameters:
- **MasterKey Scheme:** `MasterKey.KeyScheme.AES256_GCM`
- **Key Store Provider:** `AndroidKeyStore`
- **Underlying Hardware Storage:** Hardware-backed Trusted Execution Environment (TEE) or StrongBox Keymaster (where supported by SoC).
- **MasterKey Specification Code Pattern:**
```kotlin
val masterKey = MasterKey.Builder(context, "_sms_forwarder_master_key_")
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .setRequestStrongBoxStorage(true) // Uses dedicated hardware security chip if available
    .build()
```

### 4.2 EncryptedSharedPreferences Implementation Pattern
The application must maintain a dedicated vault file separate from application preferences:
```kotlin
val secureVault: SharedPreferences = EncryptedSharedPreferences.create(
    context,
    "secure_forwarder_vault",
    masterKey,
    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
)
```

### 4.3 Lock Screen Changes & Key Invalidation Safeguards
On Android, if a Keystore key is configured to require user authentication (`setUserAuthenticationRequired(true)`), changing or removing the device lock screen (PIN/pattern/biometrics) will immediately permanently invalidate or cryptographically wipe the key, causing `KeyPermanentlyInvalidatedException` and fatal service crashes in the background.

#### Architectural Mandate:
- **Do NOT bind MasterKey to biometric/lockscreen authentication for background forwarding.** Because the SMS forwarding engine must execute headless while the phone is locked, sleeping, or unattended, `setUserAuthenticationRequired` must **remain false**.
- **Resiliency & Auto-Recovery:** If a Keystore corruption or hardware failure occurs (e.g., throwing `GeneralSecurityException`), the repository must catch the exception, log an audited security error, safely purge the corrupted vault file, and notify the user via a persistent system alert requiring credential re-entry, rather than entering an unhandled crash loop.

### 4.4 Architectural Storage Separation
To prevent data contamination and simplify auditing, application storage is split into two isolated layers:

```
[ Application Layer ]
         |
         +---> Non-Sensitive State ---> Standard Room SQLite DB (`sms_forwarder.db`)
         |     - Filter conditions, regex patterns
         |     - SIM slot mapping, target URLs (non-secret parts)
         |     - Execution logs, timestamp, retry counts, error codes
         |
         +---> Sensitive Secrets    ---> EncryptedSharedPreferences (`secure_forwarder_vault`)
               - Telegram Bot Token
               - SMTP Username & Password
               - Webhook Bearer Token / API Key
               - Custom sensitive HTTP headers
```
**Strict Invariant:** Room DB entities must **NEVER** contain a column named `password`, `token`, `secret`, or `auth_header`. Room DB tables must store a string reference `secret_vault_key` (e.g., `rule_uuid_4f8a_token`), which is resolved at dispatch time from `EncryptedSharedPreferences`.

---

## 5. Log Hygiene & Data Privacy Standards

### 5.1 Strict Zero-Telemetry / Zero-Tracker Mandate
The application is designed for personal privacy.
1. **Forbidden Dependencies:**
   - No Google Analytics / Firebase Core / Crashlytics.
   - No Sentry, Bugsnag, or AppCenter.
   - No Facebook Core / Ads SDK or advertisement networks.
   - No tracking or telemetry libraries of any kind.
2. **Build Verification:**
   - Any commit adding a dependency outside the approved list (AndroidX, Kotlin Coroutines, Room, OkHttp, Security-Crypto) must fail code review and automated dependency auditing.

### 5.2 Logcat Masking & Sanitize Rules (TreeLogger Implementation)
Under no circumstances may full SMS bodies, sender telephone numbers, or authorization tokens appear in Android Logcat or exported bug reports.

#### Production Logging Standard (`SecurityLogger`):
All logging must be mediated through an audited logger tree (e.g., Timber or custom `SecurityLogTree`):
```kotlin
class SecurityLogTree : Timber.Tree() {
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        // Enforce release build filtering
        if (!BuildConfig.DEBUG && priority < Log.INFO) {
            return
        }
        val sanitized = sanitize(message)
        Log.println(priority, tag ?: "SMSForwarder", sanitized)
    }

    private fun sanitize(input: String): String {
        return input
            // Redact bot tokens: e.g. 123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ
            .replace(Regex("""\d{8,11}:[A-Za-z0-9_-]{35}"""), "[REDACTED_BOT_TOKEN]")
            // Redact Bearer tokens
            .replace(Regex("""(?i)Bearer\s+[A-Za-z0-9\-._~+/]+=*"""), "Bearer [REDACTED_TOKEN]")
            // Redact Basic Auth
            .replace(Regex("""(?i)Basic\s+[A-Za-z0-9+/=]+"""), "Basic [REDACTED]")
            // Redact phone numbers (E.164 and local)
            .replace(Regex("""(?<=\+?\d{2,3})\d{4,8}(\d{2})"""), "***$1")
            // Redact 4-8 digit potential OTPs in log strings
            .replace(Regex("""\b\d{4,8}\b"""), "[REDACTED_CODE]")
    }
}
```

#### OkHttp Logging Interceptor Safeguard:
When configuring OkHttp for Webhooks or Telegram API:
- `HttpLoggingInterceptor.Level` must be set to `Level.NONE` or `Level.BASIC` (method and URL only).
- `Level.BODY` is strictly **PROHIBITED** in production, as it serializes the raw SMS payload and authentication headers directly to Logcat.

### 5.3 Local DB Retention Policy & Pruning Engine
To prevent the SQLite database from becoming an unauthorized repository of historical telecommunications data:
1. **Default Retention Threshold:** Operational delivery logs and forwarded message metadata expire after **7 days** (configurable by the user between 24 hours and 30 days).
2. **Automated Scheduled Pruner:** A daily WorkManager `PeriodicWorkRequest` executing a hard delete query:
   ```sql
   DELETE FROM sms_records WHERE received_timestamp < :retentionCutoff;
   DELETE FROM delivery_tasks WHERE created_at < :retentionCutoff;
   VACUUM;
   ```
3. **Optional Ephemeral Mode (Zero-History Mode):** A user toggle that immediately deletes the SMS record and body from Room DB as soon as all forwarder destinations report HTTP 200 / SMTP 250 success.

### 5.4 One-Tap Total Data Wipe ("Panic Button")
The application settings must provide a secure "Factory Reset / Wipe All Data" feature:
1. Delete all Room SQLite tables and files (`sms_records`, `forwarding_rules`, `delivery_tasks`).
2. Purge and clear all `EncryptedSharedPreferences` keys.
3. Remove Keystore aliases (`KeyStore.deleteEntry("_sms_forwarder_master_key_")`).
4. Clear application cache directory (`context.cacheDir.deleteRecursively()`).
5. Cancel all pending WorkManager jobs and stop background Foreground Services.
6. Terminate process cleanly via `exitProcess(0)`.

---

## 6. Network Security Configuration & Transport Layer Security

### 6.1 Android Network Security Config
The application must declare a strict network security policy in `app/src/main/res/xml/network_security_config.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <!-- Deny cleartext HTTP across the entire application -->
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <!-- Trust only verified system root certificates -->
            <certificates src="system" />
        </trust-anchors>
    </base-config>
    
    <!-- Telegram Bot API Pinning / Domain Config -->
    <domain-config>
        <domain includeSubdomains="true">api.telegram.org</domain>
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </domain-config>
</network-security-config>
```
In `AndroidManifest.xml`:
```xml
<application
    android:networkSecurityConfig="@xml/network_security_config"
    android:usesCleartextTraffic="false"
    ... >
```

### 6.2 Strict TLS & Cipher Suite Governance
1. **Protocol Minimum:** TLS 1.2 or TLS 1.3 enforced for all network calls. TLS 1.0, 1.1, and SSLv3 are disabled.
2. **OkHttp Connection Spec:**
   ```kotlin
   val strictConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
       .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
       .cipherSuites(
           CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384,
           CipherSuite.TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384,
           CipherSuite.TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256,
           CipherSuite.TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256,
           CipherSuite.TLS_CHACHA20_POLY1305_SHA256
       )
       .build()

   val okHttpClient = OkHttpClient.Builder()
       .connectionSpecs(listOf(strictConnectionSpec))
       .connectTimeout(15, TimeUnit.SECONDS)
       .readTimeout(15, TimeUnit.SECONDS)
       .writeTimeout(15, TimeUnit.SECONDS)
       .build()
   ```
3. **Native SMTP SSLSocket:**
   When establishing direct socket connections to SMTP servers:
   ```kotlin
   val sslSocket = (SSLSocketFactory.getDefault().createSocket(host, 465) as SSLSocket).apply {
       enabledProtocols = arrayOf("TLSv1.2", "TLSv1.3")
       val sslParameters = sslParameters
       sslParameters.endpointIdentificationAlgorithm = "HTTPS" // Enforces strict SNI and X.509 hostname verification
       this.sslParameters = sslParameters
       startHandshake()
   }
   ```

---

## 7. Android OS Hardening & Manifest Declarations

### 7.1 Manifest Security Flags
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Permissions required for operation -->
    <uses-permission android:name="android.permission.RECEIVE_SMS" />
    <uses-permission android:name="android.permission.READ_PHONE_STATE" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />

    <application
        android:allowBackup="false"
        android:fullBackupContent="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:usesCleartextTraffic="false"
        android:networkSecurityConfig="@xml/network_security_config">

        <!-- Manifest Receiver protected by broadcast permission -->
        <receiver
            android:name=".receiver.SmsReceiver"
            android:exported="true"
            android:permission="android.permission.BROADCAST_SMS"
            android:priority="999">
            <intent-filter android:priority="999">
                <action android:name="android.provider.Telephony.SMS_RECEIVED" />
            </intent-filter>
        </receiver>

        <!-- Boot receiver: internal only -->
        <receiver
            android:name=".receiver.BootReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>

        <!-- Foreground Service: internal only -->
        <service
            android:name=".service.SmsForwarderService"
            android:exported="false"
            android:foregroundServiceType="specialUse" />

    </application>
</manifest>
```

### 7.2 Android 12+ (API 31+) and Android 14+ (API 34+) Compliance
- **Explicit `android:exported`:** Every component must explicitly set `android:exported="false"` unless receiving OS broadcasts.
- **PendingIntent Mutability:** Any `PendingIntent` passed to notifications must specify `PendingIntent.FLAG_IMMUTABLE` to prevent malicious intent modification.
- **Foreground Service Type:** Android 14 mandates declaring `android:foregroundServiceType="specialUse"` with `<property android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE" android:value="Personal SMS Forwarding Service" />`.

---

## 8. Auditor Quality Gate Acceptance Checklist

This checklist defines the criteria Auditor will verify before approving advancement through future project phases.

```
       [ Phase 1: Security Spec ] ===> APPROVED (Auditor)
                    |
                    v
       [ Phase 3: UI & Foundation Gate ]
         - Zero analytics libraries in gradle dependencies
         - allowBackup="false" verified in Manifest
         - MasterKey + EncryptedSharedPreferences implementation
                    |
                    v
       [ Phase 4: SMS Engine Gate ]
         - BROADCAST_SMS permission enforced on SmsReceiver
         - Logcat redaction active; zero SMS text in logs
         - Memory cleanup and non-retention verified
                    |
                    v
       [ Phase 5: Destination Integration Gate ]
         - Cleartext HTTP blocked (networkSecurityConfig)
         - Telegram HTML mode input escaping verified
         - SMTP endpoint identification (SNI/hostname verify)
         - Room DB contains zero plaintext passwords/tokens
                    |
                    v
       [ Phase 6: Reliability & Queue Gate ]
         - Exponential retry backoff protects remote APIs from storming
         - Room DB retention pruning worker functional
         - Battery optimization exemption handled gracefully
                    |
                    v
       [ Phase 7: Final Security Audit ]
         - Static analysis (no hardcoded tokens/secrets)
         - Logcat inspection during active SMS processing
         - Data wipe ("Panic Button") test verified
```

### Detailed Acceptance Checkpoints:

#### Phase 3 Gate: UI & Security Baseline
- [ ] `build.gradle.kts` audited: Zero third-party telemetry, advertising, or crash reporting SDKs present.
- [ ] `AndroidManifest.xml` enforces `android:allowBackup="false"`, `android:fullBackupContent="false"`, and `android:usesCleartextTraffic="false"`.
- [ ] Jetpack Security `MasterKey` and `EncryptedSharedPreferences` properly initialized without lock screen authentication dependencies (`setUserAuthenticationRequired(false)`).
- [ ] UI screens for credential input mask sensitive text fields (`PasswordVisualTransformation`) with toggle visibility.

#### Phase 4 Gate: SMS Ingestion & Processing Engine
- [ ] `SmsReceiver` declared with `android:permission="android.permission.BROADCAST_SMS"` to block intent injection.
- [ ] All internal components (Services, Activities, BootReceivers) declared with `android:exported="false"`.
- [ ] `goAsync()` background dispatch avoids blocking the main thread and enforces coroutine timeouts.
- [ ] Logcat output during SMS reception verified: Zero raw message bodies, zero unmasked phone numbers, zero OTP codes in log output.

#### Phase 5 Gate: Destination Integrations (Telegram, Webhook, SMTP)
- [ ] Room Database schema inspection: Tables `sms_records`, `forwarding_rules`, and `delivery_tasks` contain **zero** plaintext credentials. All tokens stored in `EncryptedSharedPreferences`.
- [ ] OkHttp client configured with `ConnectionSpec.MODERN_TLS` and `HttpLoggingInterceptor.Level.BASIC` or `NONE`.
- [ ] Telegram Bot API integration strictly uses **HTML mode** with comprehensive character escaping (`&`, `<`, `>`, `"`) to prevent injection or parse failures.
- [ ] SMTP client implements `endpointIdentificationAlgorithm = "HTTPS"` to verify server TLS certificates and prevent MitM on port 465/587.
- [ ] Non-HTTPS Webhook URLs rejected by UI validation and network security config.

#### Phase 6 Gate: Resiliency & Background Queue
- [ ] Retry queue implements exponential backoff with full jitter to avoid denial-of-service / rate-limiting bans on Telegram Bot API or Webhook endpoints.
- [ ] Daily pruning WorkManager task properly executes hard deletion of expired records based on user retention settings.
- [ ] Notification channels created with proper privacy levels; foreground service notification does not display private SMS text.

#### Phase 7 Gate: Final Security Audit & Verification
- [ ] Static source analysis: No API keys, passwords, or test tokens committed in Git repository.
- [ ] Dynamic APK inspection: Verify DEX strings and assets do not leak secrets.
- [ ] Logcat capture during end-to-end SMS forwarding cycle yields zero credential or payload leaks.
- [ ] "One-Tap Data Wipe" button verified: Deletes all Room DB entries, clears Keystore aliases, purges EncryptedSharedPreferences, and terminates process cleanly.

---

## 9. Conclusion & Recommendation for Chief

The security and privacy requirements detailed in this document are **mandatory engineering constraints**. By incorporating hardware-backed Keystore encryption, zero-leak logging hygiene, strict storage separation, and zero third-party telemetry from Day 1, the SMS Forwarder application will provide robust protection for critical personal communications and authentication credentials.

This specification is saved at:
`/home/hanzlaahmad/OpenBot/Shared/Projects/sms-forwarder/docs/PHASE_1_SECURITY_THREAT_MODEL.md`

Auditor approves completion of **TASK-PH1-AUD-01** and recommends proceeding to Phase 2 (System Architecture & Technology Selection).
