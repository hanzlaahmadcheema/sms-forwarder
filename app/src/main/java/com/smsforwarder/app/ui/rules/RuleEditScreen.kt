package com.smsforwarder.app.ui.rules

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Send
import com.smsforwarder.app.domain.model.DestinationSecrets
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.SenderFilterType
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.engine.dispatcher.TemplateRenderer
import com.smsforwarder.app.ui.theme.ErrorRed
import com.smsforwarder.app.ui.theme.SuccessGreen

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleEditScreen(
    ruleId: String?,
    viewModel: RulesViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var destinationType by remember { mutableStateOf(DestinationType.WEBHOOK) }
    var destinationEndpoint by remember { mutableStateOf("") }
    var senderFilterType by remember { mutableStateOf(SenderFilterType.ALL) }
    var senderFilterValues by remember { mutableStateOf("") }
    var keywordFilterValues by remember { mutableStateOf("") }
    var simSlotFilter by remember { mutableIntStateOf(-1) }
    var customTemplate by remember { mutableStateOf("") }

    // Secret fields
    var tgBotToken by remember { mutableStateOf("") }
    var whAuthHeader by remember { mutableStateOf("") }
    var whCustomHeaders by remember { mutableStateOf("") }
    var smtpHost by remember { mutableStateOf("") }
    var smtpPort by remember { mutableStateOf("465") }
    var smtpUser by remember { mutableStateOf("") }
    var smtpPass by remember { mutableStateOf("") }
    var smtpFrom by remember { mutableStateOf("") }
    var smtpUseStartTls by remember { mutableStateOf(false) }
    var waApiKey by remember { mutableStateOf("") }
    var isWaVerifying by remember { mutableStateOf(false) }

    var isTestingDestination by remember { mutableStateOf(false) }
    var testResultMessage by remember { mutableStateOf<String?>(null) }
    var isTestSuccess by remember { mutableStateOf(false) }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    // Load existing rule if editing
    LaunchedEffect(ruleId) {
        if (ruleId != null) {
            val rule = viewModel.loadRule(ruleId)
            if (rule != null) {
                name = rule.name
                destinationType = rule.targetDestinationType
                destinationEndpoint = rule.destinationEndpoint
                senderFilterType = rule.senderFilterType
                senderFilterValues = rule.senderFilterValues
                keywordFilterValues = rule.keywordFilterValues
                simSlotFilter = rule.simSlotFilter
                customTemplate = rule.customTemplate ?: ""

                val secrets = viewModel.loadSecrets(rule.vaultAliasRef)
                when (secrets) {
                    is DestinationSecrets.Telegram -> {
                        tgBotToken = secrets.botToken
                    }
                    is DestinationSecrets.Webhook -> {
                        whAuthHeader = secrets.authHeader ?: ""
                        whCustomHeaders = secrets.customHeadersJson ?: ""
                    }
                    is DestinationSecrets.Smtp -> {
                        smtpHost = secrets.host
                        smtpPort = secrets.port.toString()
                        smtpUser = secrets.username
                        smtpPass = secrets.password
                        smtpFrom = secrets.fromAddress
                        smtpUseStartTls = secrets.useStartTls
                    }
                    is DestinationSecrets.WhatsAppAgent -> {
                        waApiKey = secrets.apiKey
                    }
                    null -> {}
                }
            }
        } else {
            val globalSecrets = viewModel.loadSecrets(com.smsforwarder.app.ui.settings.SettingsViewModel.GLOBAL_WA_ALIAS)
            if (globalSecrets is DestinationSecrets.WhatsAppAgent) {
                if (waApiKey.isBlank()) waApiKey = globalSecrets.apiKey
                if (destinationEndpoint.isBlank() && globalSecrets.recipientId.isNotBlank()) {
                    destinationEndpoint = globalSecrets.recipientId
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (ruleId == null) "New Forwarding Rule" else "Edit Rule") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // General Rule Information
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("General Configuration", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Rule Name") },
                        placeholder = { Text("e.g., Bank OTP to Telegram") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Destination Type", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DestinationType.entries.forEach { type ->
                            FilterChip(
                                selected = destinationType == type,
                                onClick = { destinationType = type },
                                label = { Text(type.name) }
                            )
                        }
                    }
                }
            }

            // Destination Configuration & Secret Vault Inputs
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Destination & Secrets (Encrypted Vault)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    when (destinationType) {
                        DestinationType.WEBHOOK -> {
                            OutlinedTextField(
                                value = destinationEndpoint,
                                onValueChange = { destinationEndpoint = it },
                                label = { Text("Webhook URL (HTTPS Required)") },
                                placeholder = { Text("https://your-api.com/sms") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = whAuthHeader,
                                onValueChange = { whAuthHeader = it },
                                label = { Text("Authorization Header (Optional)") },
                                placeholder = { Text("Bearer secret_token_123") },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = whCustomHeaders,
                                onValueChange = { whCustomHeaders = it },
                                label = { Text("Custom JSON Headers (Optional)") },
                                placeholder = { Text("{\"X-API-Key\": \"secret\"}") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        DestinationType.TELEGRAM -> {
                            OutlinedTextField(
                                value = destinationEndpoint,
                                onValueChange = { destinationEndpoint = it },
                                label = { Text("Destination Chat ID") },
                                placeholder = { Text("-100123456789 or @channel") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = tgBotToken,
                                onValueChange = { tgBotToken = it },
                                label = { Text("Telegram Bot API Token") },
                                placeholder = { Text("123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ") },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        DestinationType.EMAIL -> {
                            OutlinedTextField(
                                value = destinationEndpoint,
                                onValueChange = { destinationEndpoint = it },
                                label = { Text("Recipient Email Address") },
                                placeholder = { Text("alerts@yourdomain.com") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = smtpHost,
                                onValueChange = { smtpHost = it },
                                label = { Text("SMTP Host") },
                                placeholder = { Text("smtp.gmail.com") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = smtpPort,
                                onValueChange = { smtpPort = it },
                                label = { Text("SMTP Port") },
                                placeholder = { Text("465 or 587") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = smtpUser,
                                onValueChange = { smtpUser = it },
                                label = { Text("SMTP Username") },
                                placeholder = { Text("user@gmail.com") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = smtpPass,
                                onValueChange = { smtpPass = it },
                                label = { Text("SMTP Password / App Password") },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = smtpFrom,
                                onValueChange = { smtpFrom = it },
                                label = { Text("From Address") },
                                placeholder = { Text("sms-forwarder@yourdomain.com") },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Use STARTTLS (Port 587)", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = smtpUseStartTls,
                                    onCheckedChange = { smtpUseStartTls = it }
                                )
                            }
                        }

                        DestinationType.WHATSAPP_AGENT -> {
                            OutlinedTextField(
                                value = waApiKey,
                                onValueChange = { waApiKey = it },
                                label = { Text("WhatsApp Agent API Key") },
                                placeholder = { Text("Agent platform API key") },
                                visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                        Icon(
                                            if (isPasswordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                            contentDescription = null
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = destinationEndpoint,
                                    onValueChange = { destinationEndpoint = it },
                                    label = { Text("Participant User ID (user.id)") },
                                    placeholder = { Text("e.g. user:123456789 or 123456789") },
                                    modifier = Modifier.weight(1f)
                                )

                                Button(
                                    onClick = {
                                        val cleanKey = waApiKey.trim()
                                        if (cleanKey.isBlank()) {
                                            validationError = "Enter WhatsApp API key first"
                                            return@Button
                                        }
                                        isWaVerifying = true
                                        coroutineScope.launch {
                                            val result = com.smsforwarder.app.SmsForwarderApp.instance.whatsAppAgentDispatcher.verifyAndDeriveRecipient(cleanKey)
                                            isWaVerifying = false
                                            if (result.isSuccess) {
                                                destinationEndpoint = result.getOrThrow()
                                                validationError = null
                                            } else {
                                                validationError = "${result.exceptionOrNull()?.message} (You can enter user.id manually)"
                                            }
                                        }
                                    },
                                    enabled = !isWaVerifying && waApiKey.isNotBlank()
                                ) {
                                    Text(if (isWaVerifying) "..." else "Auto-Derive")
                                }
                            }

                            Text(
                                text = "Enter your user.id manually or click 'Auto-Derive' to detect it from incoming WhatsApp messages.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }

            // Filtering & SIM Card Routing
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Filters & Dual-SIM Routing", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    Text("Sender Filter Mode", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SenderFilterType.entries.forEach { filterMode ->
                            FilterChip(
                                selected = senderFilterType == filterMode,
                                onClick = { senderFilterType = filterMode },
                                label = { Text(filterMode.name) }
                            )
                        }
                    }

                    if (senderFilterType != SenderFilterType.ALL) {
                        OutlinedTextField(
                            value = senderFilterValues,
                            onValueChange = { senderFilterValues = it },
                            label = { Text("Sender Numbers (Comma-separated)") },
                            placeholder = { Text("+1234567890, BANK_ALERT") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    OutlinedTextField(
                        value = keywordFilterValues,
                        onValueChange = { keywordFilterValues = it },
                        label = { Text("Keyword Filters (Comma-separated)") },
                        placeholder = { Text("OTP, verification, password") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text("Target SIM Slot", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = simSlotFilter == -1,
                            onClick = { simSlotFilter = -1 },
                            label = { Text("Any SIM") }
                        )
                        FilterChip(
                            selected = simSlotFilter == 0,
                            onClick = { simSlotFilter = 0 },
                            label = { Text("SIM 1 Only") }
                        )
                        FilterChip(
                            selected = simSlotFilter == 1,
                            onClick = { simSlotFilter = 1 },
                            label = { Text("SIM 2 Only") }
                        )
                    }
                }
            }

            // Custom Message Template Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Code, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Custom Forwarding Template", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Customize the message format sent to this destination. Insert placeholders below or pick a preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    // Placeholder Insertion Chips
                    Text("Insert Placeholder:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
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
                                onClick = { customTemplate = if (customTemplate.isBlank()) tag else "$customTemplate $tag" },
                                label = { Text(tag) }
                            )
                        }
                    }

                    // Preset Quick Pickers
                    Text(
                        text = "Template Presets:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TemplateRenderer.PRESETS.forEach { preset ->
                            FilterChip(
                                selected = customTemplate == preset.template,
                                onClick = { customTemplate = preset.template },
                                label = { Text(preset.name, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                        if (customTemplate.isNotBlank()) {
                            OutlinedButton(
                                onClick = { customTemplate = "" },
                                modifier = Modifier.height(32.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Reset", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }


                    OutlinedTextField(
                        value = customTemplate,
                        onValueChange = { customTemplate = it },
                        label = { Text("Template Body") },
                        placeholder = { Text("Leave empty for default format or use {\"sender\": \"{sender}\", \"message\": \"{body}\"}") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 6
                    )

                    // Live Preview
                    val sampleMessage = SmsMessage(
                        id = "preview-sample",
                        sender = "+15551234567",
                        rawBody = "Your verification code is 492810. Do not share.",
                        timestampMillis = System.currentTimeMillis(),
                        simSlotIndex = if (simSlotFilter >= 0) simSlotFilter else 0,
                        subId = 1,
                        carrierName = "Carrier",
                        deduplicationHash = "preview-hash",
                        createdAt = System.currentTimeMillis(),
                        contactName = "Alice Smith"
                    )

                    val renderedPreview = if (customTemplate.isNotBlank()) {
                        val isJson = customTemplate.trim().startsWith("{") || customTemplate.contains("\"")
                        TemplateRenderer.render(customTemplate, sampleMessage, escapeForJson = isJson)
                    } else {
                        when (destinationType) {
                            DestinationType.WEBHOOK -> "{\"sender\":\"+15551234567\",\"body\":\"Your verification code...\",\"message\":\"Your verification code...\"}"
                            DestinationType.TELEGRAM -> "📬 New SMS Received [SIM 1]\nFrom: +15551234567\nTime: 2026-11-10 02:00:00\n\nYour verification code is 492810. Do not share."
                            else -> TemplateRenderer.formatDefaultText(sampleMessage)
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("Live Output Preview:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(renderedPreview, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            // Quick Destination Verification Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Send, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        Text("Verify Destination", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Send a single test payload to confirm your credentials and endpoint before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    Button(
                        onClick = {
                            val app = com.smsforwarder.app.SmsForwarderApp.instance
                            isTestingDestination = true
                            testResultMessage = null

                            coroutineScope.launch {
                                try {
                                    when (destinationType) {
                                        DestinationType.WEBHOOK -> {
                                            val endpoint = destinationEndpoint.trim()
                                            if (!endpoint.startsWith("https://", ignoreCase = true)) {
                                                testResultMessage = "Error: Endpoint must begin with https://"
                                                isTestSuccess = false
                                            } else {
                                                val res = app.webhookDispatcher.sendTestWebhook(
                                                    endpoint = endpoint,
                                                    authHeader = whAuthHeader.ifBlank { null },
                                                    customHeadersJson = whCustomHeaders.ifBlank { null },
                                                    customPayload = customTemplate.ifBlank { null }
                                                )
                                                if (res.isSuccess) {
                                                    testResultMessage = "Success: ${res.getOrNull()}"
                                                    isTestSuccess = true
                                                } else {
                                                    testResultMessage = "Failed: ${res.exceptionOrNull()?.message}"
                                                    isTestSuccess = false
                                                }
                                            }
                                        }

                                        DestinationType.TELEGRAM -> {
                                            val token = tgBotToken.trim()
                                            val chat = destinationEndpoint.trim()
                                            if (token.isBlank() || chat.isBlank()) {
                                                testResultMessage = "Error: Enter Bot Token and Chat ID"
                                                isTestSuccess = false
                                            } else {
                                                val res = app.telegramDispatcher.sendTestMessage(token, chat)
                                                if (res.isSuccess) {
                                                    testResultMessage = "Success: ${res.getOrNull()}"
                                                    isTestSuccess = true
                                                } else {
                                                    testResultMessage = "Failed: ${res.exceptionOrNull()?.message}"
                                                    isTestSuccess = false
                                                }
                                            }
                                        }

                                        DestinationType.WHATSAPP_AGENT -> {
                                            val key = waApiKey.trim()
                                            val recipient = destinationEndpoint.trim()
                                            if (key.isBlank() || recipient.isBlank()) {
                                                testResultMessage = "Error: Verify recipient user ID first"
                                                isTestSuccess = false
                                            } else {
                                                val res = app.whatsAppAgentDispatcher.sendTestMessage(key, recipient)
                                                if (res.isSuccess) {
                                                    testResultMessage = "Success: ${res.getOrNull()}"
                                                    isTestSuccess = true
                                                } else {
                                                    testResultMessage = "Failed: ${res.exceptionOrNull()?.message}"
                                                    isTestSuccess = false
                                                }
                                            }
                                        }

                                        DestinationType.EMAIL -> {
                                            testResultMessage = "Email test requires saving rule and triggering dispatch."
                                            isTestSuccess = true
                                        }
                                    }
                                } catch (e: Exception) {
                                    testResultMessage = "Error: ${e.message}"
                                    isTestSuccess = false
                                } finally {
                                    isTestingDestination = false
                                }
                            }
                        },
                        enabled = !isTestingDestination && destinationEndpoint.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isTestingDestination) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = MaterialTheme.colorScheme.onPrimary)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Send Test Payload to ${destinationType.name}")
                    }

                    if (testResultMessage != null) {
                        Text(
                            text = testResultMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isTestSuccess) SuccessGreen else ErrorRed
                        )
                    }
                }
            }

            if (validationError != null) {
                Text(
                    text = validationError ?: "",
                    color = ErrorRed,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            // Save Action Button
            Button(
                onClick = {
                    // Validation
                    if (name.isBlank()) {
                        validationError = "Rule name cannot be empty"
                        return@Button
                    }
                    if (destinationEndpoint.isBlank()) {
                        validationError = "Destination endpoint cannot be empty"
                        return@Button
                    }
                    if (destinationType == DestinationType.WEBHOOK && !destinationEndpoint.startsWith("https://", ignoreCase = true)) {
                        validationError = "Webhook URL must strictly use HTTPS"
                        return@Button
                    }
                    if (destinationType == DestinationType.TELEGRAM && tgBotToken.isBlank()) {
                        validationError = "Telegram Bot Token is required"
                        return@Button
                    }
                    if (destinationType == DestinationType.EMAIL && (smtpHost.isBlank() || smtpUser.isBlank() || smtpPass.isBlank())) {
                        validationError = "SMTP Host, Username, and Password are required"
                        return@Button
                    }
                    if (destinationType == DestinationType.WHATSAPP_AGENT && waApiKey.isBlank()) {
                        validationError = "WhatsApp Agent API key is required"
                        return@Button
                    }

                    val finalEndpoint = if (destinationType == DestinationType.WHATSAPP_AGENT) {
                        com.smsforwarder.app.SmsForwarderApp.instance.whatsAppAgentDispatcher.formatParticipantId(destinationEndpoint)
                    } else {
                        destinationEndpoint.trim()
                    }

                    val secrets: DestinationSecrets = when (destinationType) {
                        DestinationType.WEBHOOK -> DestinationSecrets.Webhook(
                            authHeader = whAuthHeader.ifBlank { null },
                            customHeadersJson = whCustomHeaders.ifBlank { null }
                        )
                        DestinationType.TELEGRAM -> DestinationSecrets.Telegram(
                            botToken = tgBotToken.trim()
                        )
                        DestinationType.EMAIL -> DestinationSecrets.Smtp(
                            host = smtpHost.trim(),
                            port = smtpPort.toIntOrNull() ?: 465,
                            username = smtpUser.trim(),
                            password = smtpPass,
                            fromAddress = smtpFrom.ifBlank { smtpUser }.trim(),
                            useStartTls = smtpUseStartTls
                        )
                        DestinationType.WHATSAPP_AGENT -> DestinationSecrets.WhatsAppAgent(
                            apiKey = waApiKey.trim(),
                            recipientId = finalEndpoint
                        )
                    }

                    viewModel.saveRule(
                        existingId = ruleId,
                        name = name.trim(),
                        destinationType = destinationType,
                        destinationEndpoint = finalEndpoint,
                        senderFilterType = senderFilterType,
                        senderFilterValues = senderFilterValues.trim(),
                        keywordFilterValues = keywordFilterValues.trim(),
                        simSlotFilter = simSlotFilter,
                        customTemplate = customTemplate.ifBlank { null },
                        secrets = secrets,
                        onSuccess = { onNavigateBack() },
                        onError = { validationError = it }
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save Forwarding Rule", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
