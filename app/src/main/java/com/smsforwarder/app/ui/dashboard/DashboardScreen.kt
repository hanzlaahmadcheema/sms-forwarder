package com.smsforwarder.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.smsforwarder.app.core.util.SecurityLogTree
import com.smsforwarder.app.domain.model.DestinationType
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.ui.theme.ErrorRed
import com.smsforwarder.app.ui.theme.SuccessGreen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper
import com.smsforwarder.app.ui.theme.WarningAmber

@Composable

fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.refreshSystemStatus(context)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Master Service Status Banner
        item {
            ServiceStatusBanner(
                isActive = state.isForwardingServiceActive,
                onToggle = { viewModel.toggleMasterForwarding(it) }
            )
        }

        // 2. Beginner Step-by-Step Onboarding Checklist Guide
        item {
            GettingStartedGuideCard(
                state = state,
                onToggleCollapse = { viewModel.toggleGuideCollapse() },
                onRefresh = { viewModel.refreshSystemStatus(context) },
                onStartService = { viewModel.toggleMasterForwarding(true) }
            )
        }

        // 2. Metrics Statistics Row
        item {
            MetricsRow(
                received = state.totalReceivedCount,
                success = state.totalSuccessCount,
                failed = state.totalFailedCount
            )
        }

        // 3. Active Destinations Summary
        item {
            Text(
                text = "Active Destinations (${state.activeRules.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.activeRules.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "No active forwarding rules. Configure rules in the Rules tab.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(state.activeRules) { rule ->
                ActiveRuleSummaryItem(rule = rule)
            }
        }

        // 4. Recent Activity
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Recent Message Activity",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        if (state.recentSms.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Text(
                        text = "No incoming SMS received yet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            items(state.recentSms) { sms ->
                RecentSmsItem(sms = sms)
            }
        }
    }
}

@Composable
fun ServiceStatusBanner(
    isActive: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (isActive) SuccessGreen else ErrorRed)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (isActive) "Forwarder Active" else "Forwarder Paused",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (isActive) SuccessGreen else ErrorRed
                    )
                    Text(
                        text = if (isActive) "Listening for incoming SMS" else "SMS interception suspended",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
            }

            Switch(
                checked = isActive,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun MetricsRow(
    received: Int,
    success: Int,
    failed: Int
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricCard(
            title = "Received",
            value = received.toString(),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            title = "Forwarded",
            value = success.toString(),
            color = SuccessGreen,
            modifier = Modifier.weight(1f)
        )
        MetricCard(
            title = "Failed",
            value = failed.toString(),
            color = ErrorRed,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun ActiveRuleSummaryItem(rule: ForwardingRule) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = rule.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "${rule.targetDestinationType.name} • ${rule.destinationEndpoint}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun RecentSmsItem(sms: SmsMessage) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    val formattedDate = dateFormat.format(Date(sms.timestampMillis))
    // Sanitize phone number in UI according to security specs
    val sanitizedSender = SecurityLogTree.sanitize(sms.sender)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = sanitizedSender,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            if (sms.simSlotIndex >= 0) {
                Text(
                    text = "SIM ${sms.simSlotIndex + 1} (${sms.carrierName ?: "Carrier"})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = sms.rawBody,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2
            )
        }
    }
}

@Composable
fun GettingStartedGuideCard(
    state: DashboardUiState,
    onToggleCollapse: () -> Unit,
    onRefresh: () -> Unit,
    onStartService: () -> Unit
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        onRefresh()
    }

    val smsGranted = state.isSmsPermissionGranted && state.isPhoneStatePermissionGranted
    val batteryExempt = state.isBatteryExempt
    val serviceActive = state.isForwardingServiceActive
    val hasRules = state.activeRules.isNotEmpty()
    val hasForwarded = state.totalSuccessCount > 0

    val completedSteps = listOf(smsGranted, batteryExempt, serviceActive, hasRules, hasForwarded).count { it }
    val progress = completedSteps / 5f

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "🚀 Quick Setup & Onboarding",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Steps completed: $completedSteps of 5",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh Status",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onToggleCollapse) {
                        Icon(
                            imageVector = if (state.isGuideCollapsed) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                            contentDescription = if (state.isGuideCollapsed) "Expand Guide" else "Collapse Guide"
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (completedSteps == 5) SuccessGreen else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
            )

            if (!state.isGuideCollapsed) {
                Spacer(modifier = Modifier.height(14.dp))

                // Step 1: SMS & Phone State Permissions
                ChecklistStepItem(
                    isCompleted = smsGranted,
                    title = "1. SMS & Phone Permissions",
                    description = "Required to capture incoming SMS and detect dual-SIM carriers.",
                    actionLabel = if (!smsGranted) "Grant Permissions" else null,
                    onAction = {
                        val perms = mutableListOf(
                            Manifest.permission.RECEIVE_SMS,
                            Manifest.permission.READ_PHONE_STATE
                        )
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            perms.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        permissionLauncher.launch(perms.toTypedArray())
                    }
                )

                // Step 2: Battery Optimization Exemption
                ChecklistStepItem(
                    isCompleted = batteryExempt,
                    title = "2. Background Battery Exemption",
                    description = "Prevents Android battery saver from killing the forwarder engine in deep sleep.",
                    actionLabel = if (!batteryExempt) "Disable Restrictions" else null,
                    onAction = {
                        BatteryOptimizationHelper.openBatterySettings(context)
                        onRefresh()
                    }
                )

                // Step 3: Forwarding Background Service
                ChecklistStepItem(
                    isCompleted = serviceActive,
                    title = "3. Active Background Engine",
                    description = "Foreground notification and persistent queue drainer.",
                    actionLabel = if (!serviceActive) "Start Engine" else null,
                    onAction = onStartService
                )

                // Step 4: Configure Rules
                ChecklistStepItem(
                    isCompleted = hasRules,
                    title = "4. Forwarding Rule Configured",
                    description = if (hasRules) "${state.activeRules.size} active rule(s) configured." else "Add a rule via Rules tab or Quick JSON setup.",
                    actionLabel = null,
                    onAction = {}
                )

                // Step 5: First Message Forwarded
                ChecklistStepItem(
                    isCompleted = hasForwarded,
                    title = "5. First Delivery Verification",
                    description = if (hasForwarded) "${state.totalSuccessCount} message(s) successfully forwarded!" else "Send an SMS to this device to test end-to-end forwarding.",
                    actionLabel = null,
                    onAction = {}
                )
            }
        }
    }
}

@Composable
private fun ChecklistStepItem(
    isCompleted: Boolean,
    title: String,
    description: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isCompleted) Icons.Default.CheckCircle else Icons.Default.Warning,
            contentDescription = null,
            tint = if (isCompleted) SuccessGreen else WarningAmber,
            modifier = Modifier.size(22.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
        if (actionLabel != null) {
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(34.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

