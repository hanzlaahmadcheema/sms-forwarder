package com.smsforwarder.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import com.smsforwarder.app.engine.dispatcher.TemplateRenderer

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.smsforwarder.app.engine.service.SmsForwarderService
import com.smsforwarder.app.ui.theme.ErrorRed
import com.smsforwarder.app.ui.theme.SuccessGreen

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showWipeConfirmDialog by remember { mutableStateOf(false) }

    // Check battery optimization status
    LaunchedEffect(Unit) {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnored = powerManager.isIgnoringBatteryOptimizations(context.packageName)
        viewModel.setBatteryOptimizationStatus(isIgnored)
    }

    var isApiKeyVisible by remember { mutableStateOf(false) }
    var testWebhookUrl by remember { mutableStateOf("https://sms-relay.raza-olakh.workers.dev/sms-9f3ka71qz") }
    var testWebhookAuth by remember { mutableStateOf("") }
    var testWebhookPayload by remember { mutableStateOf("{\"sender\": \"+15551234567\", \"message\": \"Test verification from SMS Forwarder\"}") }
    var testTgBotToken by remember { mutableStateOf("") }
    var testTgChatId by remember { mutableStateOf("") }
    var globalTemplateInput by remember(state.defaultTemplate) { mutableStateOf(state.defaultTemplate) }

    val clipboardManager = LocalClipboardManager.current
    var showExportDialog by remember { mutableStateOf(false) }
    var exportedJsonText by remember { mutableStateOf("") }
    var showRestoreDialog by remember { mutableStateOf(false) }
    var restoreInputText by remember { mutableStateOf("") }
    var restoreResultMessage by remember { mutableStateOf<String?>(null) }
    var isRestoreSuccess by remember { mutableStateOf(false) }


    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Settings & Configuration",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // 1. Network & Timeout Reliability Card (Detailed Settings)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.NetworkCheck, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Network & Dispatch Reliability", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Configure network socket timeouts and deduplication windows to prevent double-sends under varying cellular latency.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Text("Socket Timeout Duration", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 45, 60).forEach { seconds ->
                        FilterChip(
                            selected = state.networkTimeoutSeconds == seconds,
                            onClick = { viewModel.updateNetworkTimeout(seconds) },
                            label = { Text("${seconds}s") }
                        )
                    }
                }

                Text("SMS Deduplication Window", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 120, 300).forEach { seconds ->
                        FilterChip(
                            selected = state.deduplicationWindowSeconds == seconds,
                            onClick = { viewModel.updateDeduplicationWindow(seconds) },
                            label = { Text("${seconds}s") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Prevent Telegram Double-Send on Timeout", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "When Telegram API read times out, the message often arrived. Disabling retry prevents sending duplicate messages.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = !state.telegramRetryOnTimeout,
                        onCheckedChange = { preventDuplicate ->
                            viewModel.toggleTelegramRetryOnTimeout(!preventDuplicate)
                        }
                    )
                }
            }
        }

        // 2. Global Default Message Template Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Global Default Message Template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Fallback formatting applied to all destinations that do not specify a rule-level template.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                // Preset Quick Pickers
                Text("Template Presets:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TemplateRenderer.PRESETS.forEach { preset ->
                        FilterChip(
                            selected = globalTemplateInput == preset.template,
                            onClick = { globalTemplateInput = preset.template },
                            label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                // Placeholder tokens
                Text("Insert Variables:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    listOf(
                        "{contactName}",
                        "{senderNumber}",
                        "{body}",
                        "{date}",
                        "{day}",
                        "{time}",
                        "{sim}",
                        "{carrier}",
                        "{timestamp}",
                        "{hash}"
                    ).forEach { tag ->
                        FilterChip(
                            selected = false,
                            onClick = { globalTemplateInput = if (globalTemplateInput.isBlank()) tag else "$globalTemplateInput $tag" },
                            label = { Text(tag, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }


                OutlinedTextField(
                    value = globalTemplateInput,
                    onValueChange = { globalTemplateInput = it },
                    label = { Text("Global Template") },
                    placeholder = { Text("Leave blank for system default, or use {\"sender\": \"{sender}\", \"message\": \"{body}\"}") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 4
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.updateDefaultTemplate(globalTemplateInput) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save Default Template")
                    }
                    if (globalTemplateInput.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                globalTemplateInput = ""
                                viewModel.updateDefaultTemplate("")
                            }
                        ) {
                            Text("Clear")
                        }
                    }
                }
            }
        }

        // 3. Quick Webhook & Cloudflare Relay Tester Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Quick Webhook & Relay Tester", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Directly test any HTTP webhook or Cloudflare WhatsApp relay worker. Verified working relay: sms-relay.raza-olakh.workers.dev",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = testWebhookUrl,
                    onValueChange = { testWebhookUrl = it },
                    label = { Text("Webhook Target URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = testWebhookPayload,
                    onValueChange = { testWebhookPayload = it },
                    label = { Text("JSON Test Payload") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { viewModel.testWebhook(testWebhookUrl, testWebhookAuth.ifBlank { null }, testWebhookPayload.ifBlank { null }) },
                    enabled = !state.isWebhookTesting && testWebhookUrl.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isWebhookTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Send Test Webhook POST")
                }

                if (state.webhookTestResult != null) {
                    Text(
                        text = state.webhookTestResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.isWebhookTestSuccess) SuccessGreen else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 4. Quick Telegram Tester Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Quick Telegram Bot Tester", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "Test your Telegram bot token and chat ID before creating rules.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = testTgBotToken,
                    onValueChange = { testTgBotToken = it },
                    label = { Text("Telegram Bot Token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = testTgChatId,
                    onValueChange = { testTgChatId = it },
                    label = { Text("Telegram Chat ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = { viewModel.testTelegram(testTgBotToken, testTgChatId) },
                    enabled = !state.isTelegramTesting && testTgBotToken.isNotBlank() && testTgChatId.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (state.isTelegramTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Send Test Telegram Message")
                }

                if (state.telegramTestResult != null) {
                    Text(
                        text = state.telegramTestResult ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (state.isTelegramTestSuccess) SuccessGreen else MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        // 5. WhatsApp Agent Platform Integration Card (Native Meta API)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("WhatsApp Agent Platform (Native Meta API)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text(
                    text = "For Cloudflare relay workers (like sms-relay.raza-olakh.workers.dev), create a Webhook rule instead. Use this section only for Meta's native WhatsApp Agent Platform API key.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                OutlinedTextField(
                    value = state.waApiKeyInput,
                    onValueChange = { viewModel.onWaApiKeyChange(it) },
                    label = { Text("WhatsApp Agent API Key") },
                    placeholder = { Text("Paste your API key here") },
                    visualTransformation = if (isApiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { isApiKeyVisible = !isApiKeyVisible }) {
                            Icon(
                                if (isApiKeyVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (isApiKeyVisible) "Hide key" else "Show key"
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = state.waRecipientInput,
                    onValueChange = { viewModel.onWaRecipientIdChange(it) },
                    label = { Text("Participant User ID (user.id)") },
                    placeholder = { Text("e.g. user:123456789 or 123456789") },
                    supportingText = {
                        Text("Enter your user.id manually or click Auto-Derive to detect from incoming WhatsApp messages.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (state.isWaVerified && !state.waRecipientId.isNullOrBlank()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Linked Recipient: ${state.waRecipientId}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = SuccessGreen
                        )
                    }
                }

                if (state.waStatusMessage != null) {
                    Text(
                        text = state.waStatusMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (state.isWaVerified) SuccessGreen else MaterialTheme.colorScheme.error
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { viewModel.saveWhatsAppAgentConfig() },
                        enabled = !state.isWaLoading && state.waApiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Save")
                    }

                    OutlinedButton(
                        onClick = { viewModel.verifyAndLinkWhatsApp() },
                        enabled = !state.isWaLoading && state.waApiKeyInput.isNotBlank(),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        if (state.isWaLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text("Auto-Derive")
                    }

                    Button(
                        onClick = { viewModel.sendWhatsAppTestMessage() },
                        enabled = !state.isWaLoading && state.waApiKeyInput.isNotBlank() && (state.waRecipientInput.isNotBlank() || state.isWaVerified),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Text("Send Test")
                    }
                }
            }
        }

        // 1. Background Execution & Battery Optimization
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Background Execution", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Persistent Foreground Service", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Runs background service with ongoing status notification",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = state.isServiceRunning,
                        onCheckedChange = { running ->
                            viewModel.toggleServiceState(running)
                            if (running) {
                                SmsForwarderService.start(context)
                            } else {
                                SmsForwarderService.stop(context)
                            }
                        }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Battery Optimization Exemption", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            if (state.isBatteryOptimizationIgnored) "Exempted (Immune to Doze mode)" else "Restricted (May delay forwarding when idle)",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (state.isBatteryOptimizationIgnored) SuccessGreen else MaterialTheme.colorScheme.error
                        )
                    }

                    if (!state.isBatteryOptimizationIgnored) {
                        OutlinedButton(onClick = {
                            com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper.openBatterySettings(context)
                        }) {
                            Text("Exempt")
                        }
                    }
                }
            }
        }

        // 2. Data Retention & Zero-History Mode
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Data Retention & Privacy", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                Text("Message History Retention", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 7, 14, 30).forEach { days ->
                        FilterChip(
                            selected = state.retentionDays == days,
                            onClick = { viewModel.updateRetentionDays(days) },
                            label = { Text("$days Days") }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Zero-History Mode (Ephemeral)", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                        Text(
                            "Purges SMS message body immediately after successful dispatch",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    Switch(
                        checked = state.isEphemeralMode,
                        onCheckedChange = { viewModel.toggleEphemeralMode(it) }
                    )
                }
            }
        }

        // 3. Security Standards & Privacy Invariants
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Audited Privacy Guarantees", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text("• Zero third-party telemetry, analytics, or tracking SDKs.", style = MaterialTheme.typography.bodySmall)
                Text("• Passwords and tokens encrypted via Android Keystore AES-256-GCM.", style = MaterialTheme.typography.bodySmall)
                Text("• System-wide cleartext traffic strictly prohibited (HTTPS/TLS only).", style = MaterialTheme.typography.bodySmall)
                Text("• SMS bodies, phone numbers, and OTP codes redacted from system logs.", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Backup & Configuration Restore Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Backup & Configuration Restore", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Text(
                    text = "Export forwarding rules and system preferences to a portable JSON backup, or restore a previously saved configuration.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            viewModel.exportBackupJson { json ->
                                exportedJsonText = json
                                clipboardManager.setText(AnnotatedString(json))
                                showExportDialog = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Backup, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export JSON")
                    }

                    Button(
                        onClick = {
                            restoreInputText = ""
                            restoreResultMessage = null
                            isRestoreSuccess = false
                            showRestoreDialog = true
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore JSON")
                    }
                }
            }
        }

        // 4. One-Tap Factory Reset / Panic Button
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteForever, contentDescription = null, tint = ErrorRed)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text("Emergency Data Wipe", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = ErrorRed)
                }
                Text(
                    "Irrevocably deletes all local SMS records, forwarding rules, delivery queues, and clears the hardware Keystore vault.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Button(
                    onClick = { showWipeConfirmDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = ErrorRed),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Factory Reset / Wipe All Data", fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    // Export Dialog
    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            icon = { Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Backup Exported", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "✓ Configuration copied to clipboard! You can save this JSON snippet safely to restore anytime.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessGreen,
                        fontWeight = FontWeight.Medium
                    )
                    OutlinedTextField(
                        value = exportedJsonText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Backup JSON") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 10
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(exportedJsonText))
                        showExportDialog = false
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Copy & Close")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) {
                    Text("Close")
                }
            }
        )
    }

    // Restore Dialog
    if (showRestoreDialog) {
        AlertDialog(
            onDismissRequest = { showRestoreDialog = false },
            icon = { Icon(Icons.Default.Restore, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            title = { Text("Restore Configuration", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Paste a previously exported JSON backup below. Rules and preferences will be imported immediately.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                    OutlinedTextField(
                        value = restoreInputText,
                        onValueChange = {
                            restoreInputText = it
                            restoreResultMessage = null
                        },
                        label = { Text("Backup JSON Content") },
                        placeholder = { Text("{\n  \"app\": \"smsforwarder\",\n  \"preferences\": {...},\n  \"rules\": [...]\n}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 10
                    )

                    if (restoreResultMessage != null) {
                        Text(
                            text = restoreResultMessage!!,
                            color = if (isRestoreSuccess) SuccessGreen else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            },
            confirmButton = {
                if (!isRestoreSuccess) {
                    Button(
                        onClick = {
                            viewModel.restoreBackupJson(restoreInputText) { success, msg ->
                                isRestoreSuccess = success
                                restoreResultMessage = msg
                            }
                        },
                        enabled = restoreInputText.isNotBlank()
                    ) {
                        Text("Restore Now")
                    }
                } else {
                    Button(onClick = { showRestoreDialog = false }) {
                        Text("Done")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestoreDialog = false }) {
                    Text(if (isRestoreSuccess) "Close" else "Cancel")
                }
            }
        )
    }

    if (showWipeConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showWipeConfirmDialog = false },
            title = { Text("Confirm Total Data Wipe") },
            text = { Text("Are you completely sure? This will purge all forwarding rules, credentials, and message history from this device permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWipeConfirmDialog = false
                        viewModel.executePanicDataWipe {
                            SmsForwarderService.stop(context)
                        }
                    }
                ) {
                    Text("Wipe Everything", color = ErrorRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showWipeConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

