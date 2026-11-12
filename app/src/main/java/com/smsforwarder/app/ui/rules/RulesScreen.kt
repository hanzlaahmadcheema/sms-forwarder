package com.smsforwarder.app.ui.rules

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.ui.theme.ErrorRed
import com.smsforwarder.app.ui.theme.SuccessGreen

@Composable
fun RulesScreen(
    viewModel: RulesViewModel,
    onNavigateToEditRule: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    var showQuickJsonDialog by remember { mutableStateOf(false) }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = { onNavigateToEditRule(null) },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Forwarding Rule")
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Forwarding Destinations",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Configure rules to forward SMS to Webhooks, Telegram, WhatsApp or Email",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(10.dp))

                // Quick Setup Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { onNavigateToEditRule(null) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Add Rule")
                    }
                    OutlinedButton(
                        onClick = { showQuickJsonDialog = true },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("⚡ Paste JSON")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }


            if (state.rules.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No rules configured yet.",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Tap the + button below to create your first forwarding destination.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            } else {
                items(state.rules) { rule ->
                    RuleCard(
                        rule = rule,
                        onToggle = { viewModel.toggleRule(rule.id, it) },
                        onEdit = { onNavigateToEditRule(rule.id) },
                        onDelete = { viewModel.deleteRule(rule.id, rule.vaultAliasRef) }
                    )
                }
            }
        }
    }

    if (showQuickJsonDialog) {
        QuickJsonRuleDialog(
            onDismiss = { showQuickJsonDialog = false },
            onImport = { json, onSuccess, onError ->
                viewModel.importRuleFromJson(json, onSuccess, onError)
            }
        )
    }
}

@Composable
fun RuleCard(
    rule: ForwardingRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (rule.targetDestinationType) {
                            DestinationType.WEBHOOK -> Icons.Default.Send
                            DestinationType.TELEGRAM -> Icons.Default.Send
                            DestinationType.EMAIL -> Icons.Default.Email
                            DestinationType.WHATSAPP_AGENT -> Icons.Default.Send
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = rule.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = rule.targetDestinationType.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Switch(
                    checked = rule.isEnabled,
                    onCheckedChange = onToggle
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Target: ${rule.destinationEndpoint}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
            )

            if (rule.senderFilterValues.isNotBlank()) {
                Text(
                    text = "Filter: ${rule.senderFilterType.name} (${rule.senderFilterValues})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            if (rule.simSlotFilter >= 0) {
                Text(
                    text = "SIM Slot: SIM ${rule.simSlotFilter + 1} only",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Rule", tint = MaterialTheme.colorScheme.primary)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Rule", tint = ErrorRed)
                }
            }
        }
    }
}

@Composable
fun QuickJsonRuleDialog(
    onDismiss: () -> Unit,
    onImport: (String, (String) -> Unit, (String) -> Unit) -> Unit
) {
    var jsonInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.FlashOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("⚡ Quick JSON Setup", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Paste a JSON configuration below or tap a preset to load a pre-configured template, then tap 'Boom! Setup Rule'.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                )

                // Quick Preset Chips
                Text(
                    text = "Quick Presets:",
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
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "settings": {
    "defaultTemplate": "{senderNumber}\n{contactName}\n\n{body}\n\n{date}\n{day}\n{time}",
    "retentionDays": 7,
    "deduplicationWindowSeconds": 60
  },
  "providers": [
    {
      "name": "Discord Webhook",
      "type": "WEBHOOK",
      "endpoint": "https://discord.com/api/webhooks/your/id/token",
      "template": "{\"content\": \"📩 **SMS from {senderNumber}** ({contactName}):\n> {body}\"}"
    },
    {
      "name": "Telegram Alert",
      "type": "TELEGRAM",
      "endpoint": "YOUR_CHAT_ID",
      "bot_token": "YOUR_BOT_TOKEN",
      "template": "*📬 New SMS Alert* [SIM {sim}]\n*From:* `{senderNumber}` ({contactName})\n*Time:* {date} • {time}\n\n{body}"
    },
    {
      "name": "WhatsApp Agent",
      "type": "WHATSAPP_AGENT",
      "endpoint": "user:123456",
      "api_key": "YOUR_KEY",
      "template": "🚨 SMS from {senderNumber} ({contactName}): {body}"
    }
  ]
}"""
                            errorMessage = null
                        },
                        label = { Text("⚡ Multi-Provider Bundle", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "name": "Discord Webhook",
  "type": "WEBHOOK",
  "endpoint": "https://discord.com/api/webhooks/your/id/token",
  "template": "{\"content\": \"📩 **SMS from {senderNumber}** ({contactName}):\n> {body}\"}"
}"""
                            errorMessage = null
                        },
                        label = { Text("Discord", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "name": "Telegram Bot",
  "type": "TELEGRAM",
  "endpoint": "YOUR_CHAT_ID",
  "bot_token": "YOUR_BOT_TOKEN",
  "template": "*📬 New SMS Alert* [SIM {sim}]\n*From:* `{senderNumber}` ({contactName})\n\n{body}"
}"""
                            errorMessage = null
                        },
                        label = { Text("Telegram", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "name": "WhatsApp Agent",
  "type": "WHATSAPP_AGENT",
  "endpoint": "user:123456",
  "api_key": "YOUR_API_KEY",
  "template": "🚨 SMS from {senderNumber} ({contactName}) [{sim}]: {body}"
}"""
                            errorMessage = null
                        },
                        label = { Text("WhatsApp", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "name": "Slack Channel",
  "type": "WEBHOOK",
  "endpoint": "https://hooks.slack.com/services/...",
  "template": "{\"text\": \"📩 *SMS from {senderNumber}* ({contactName}) [{sim}]:\n>{body}\"}"
}"""
                            errorMessage = null
                        },
                        label = { Text("Slack", style = MaterialTheme.typography.labelSmall) }
                    )
                    FilterChip(
                        selected = false,
                        onClick = {
                            jsonInput = """{
  "name": "Custom REST API",
  "type": "WEBHOOK",
  "endpoint": "https://api.yourdomain.com/v1/sms",
  "secrets": {
    "auth_header": "Bearer YOUR_SECRET_TOKEN"
  },
  "template": "{\"sender\": \"{senderNumber}\", \"contact\": \"{contactName}\", \"body\": \"{body}\", \"sim\": \"{sim}\", \"time\": \"{time}\"}"
}"""
                            errorMessage = null
                        },
                        label = { Text("API Webhook", style = MaterialTheme.typography.labelSmall) }
                    )
                }

                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = {
                        jsonInput = it
                        errorMessage = null
                    },
                    label = { Text("Paste JSON Here") },
                    placeholder = { Text("{\n  \"name\": \"My Destination\",\n  \"type\": \"WEBHOOK\",\n  \"endpoint\": \"https://...\"\n}") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 6,
                    maxLines = 10
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage!!,
                        color = ErrorRed,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                if (successMessage != null) {
                    Text(
                        text = "✓ $successMessage",
                        color = SuccessGreen,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        },
        confirmButton = {
            if (successMessage == null) {
                Button(
                    onClick = {
                        onImport(
                            jsonInput,
                            { successMsg ->
                                successMessage = successMsg
                                errorMessage = null
                            },
                            { errorMsg ->
                                errorMessage = errorMsg
                                successMessage = null
                            }
                        )
                    }
                ) {
                    Text("Boom! Setup All")
                }
            } else {
                Button(onClick = onDismiss) {
                    Text("Done")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(if (successMessage != null) "Close" else "Cancel")
            }
        }
    )
}

