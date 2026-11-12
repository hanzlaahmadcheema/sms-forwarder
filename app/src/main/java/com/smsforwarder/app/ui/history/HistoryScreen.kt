package com.smsforwarder.app.ui.history

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
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.TaskStatus
import com.smsforwarder.app.ui.theme.ErrorRed
import com.smsforwarder.app.ui.theme.SuccessGreen
import com.smsforwarder.app.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.smsforwarder.app.domain.model.SmsMessage
import kotlinx.coroutines.launch

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var selectedTaskForDetails by remember { mutableStateOf<DeliveryTask?>(null) }
    var associatedSms by remember { mutableStateOf<SmsMessage?>(null) }

    LaunchedEffect(selectedTaskForDetails) {
        val task = selectedTaskForDetails
        if (task != null) {
            associatedSms = viewModel.getSmsForTask(task.smsRecordId)
        } else {
            associatedSms = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Delivery History & Logs",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "${state.totalCount} records • ${state.successCount} success • ${state.failureCount} failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val hasPendingOrFailed = state.failureCount > 0 || (state.totalCount > (state.successCount + state.failureCount))
                if (hasPendingOrFailed) {
                    IconButton(onClick = { viewModel.flushOrRetryAll() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry / Flush Queue",
                            tint = WarningAmber
                        )
                    }
                }
                if (state.totalCount > 0) {
                    IconButton(onClick = { viewModel.clearAllHistory() }) {
                        Icon(
                            Icons.Default.DeleteSweep,
                            contentDescription = "Clear History",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search Bar
        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { viewModel.onSearchQueryChange(it) },
            placeholder = { Text("Search logs by endpoint, destination, error...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (state.searchQuery.isNotBlank()) {
                    IconButton(onClick = { viewModel.onSearchQueryChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Status Filter Chips
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = state.selectedFilter == null,
                onClick = { viewModel.setFilter(null) },
                label = { Text("All (${state.totalCount})") }
            )
            FilterChip(
                selected = state.selectedFilter == TaskStatus.SUCCESS,
                onClick = { viewModel.setFilter(TaskStatus.SUCCESS) },
                label = { Text("Success (${state.successCount})") }
            )
            FilterChip(
                selected = state.selectedFilter == TaskStatus.FAILED_RETRYABLE || state.selectedFilter == TaskStatus.FAILED_EXHAUSTED,
                onClick = { viewModel.setFilter(TaskStatus.FAILED_EXHAUSTED) },
                label = { Text("Failed (${state.failureCount})") }
            )
            FilterChip(
                selected = state.selectedFilter == TaskStatus.PENDING,
                onClick = { viewModel.setFilter(TaskStatus.PENDING) },
                label = { Text("Pending") }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (state.tasks.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (state.searchQuery.isBlank()) "No delivery events recorded." else "No records matching '${state.searchQuery}'",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.tasks) { task ->
                    DeliveryTaskCard(
                        task = task,
                        onClick = { selectedTaskForDetails = task },
                        onRetry = { viewModel.retryTask(task.id) }
                    )
                }
            }
        }
    }

    // Detailed Log Dialog
    if (selectedTaskForDetails != null) {
        val task = selectedTaskForDetails!!
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val createdStr = dateFormat.format(Date(task.createdAt))
        val attemptStr = task.lastAttemptAt?.let { dateFormat.format(Date(it)) } ?: "Never"

        val logSummary = buildString {
            appendLine("=== SMS Forwarder Diagnostic Log ===")
            appendLine("Task ID: ${task.id}")
            appendLine("Status: ${task.status.name}")
            appendLine("Destination: ${task.destinationType.name}")
            appendLine("Endpoint: ${task.destinationEndpoint}")
            appendLine("Created: $createdStr")
            appendLine("Last Attempt: $attemptStr")
            appendLine("Attempt Count: ${task.attemptCount}")
            if (!task.errorMessage.isNullOrBlank()) {
                appendLine("Error Diagnostic: ${task.errorMessage}")
            }
            if (associatedSms != null) {
                appendLine("--- SMS Metadata ---")
                appendLine("Sender: ${associatedSms!!.sender}")
                appendLine("SIM Slot: ${if (associatedSms!!.simSlotIndex >= 0) "SIM " + (associatedSms!!.simSlotIndex + 1) else "Unknown"}")
                appendLine("Carrier: ${associatedSms!!.carrierName ?: "N/A"}")
                appendLine("Body: ${associatedSms!!.rawBody}")
            }
        }

        AlertDialog(
            onDismissRequest = { selectedTaskForDetails = null },
            icon = {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = when (task.status) {
                        TaskStatus.SUCCESS -> SuccessGreen
                        TaskStatus.PENDING, TaskStatus.IN_PROGRESS -> WarningAmber
                        TaskStatus.FAILED_RETRYABLE, TaskStatus.FAILED_EXHAUSTED -> ErrorRed
                    }
                )
            },
            title = { Text("Delivery Diagnostic Log") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Status:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = task.status.name,
                            fontWeight = FontWeight.Bold,
                            color = when (task.status) {
                                TaskStatus.SUCCESS -> SuccessGreen
                                TaskStatus.PENDING, TaskStatus.IN_PROGRESS -> WarningAmber
                                TaskStatus.FAILED_RETRYABLE, TaskStatus.FAILED_EXHAUSTED -> ErrorRed
                            }
                        )
                    }

                    Text("Destination: ${task.destinationType.name}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                    Text("Endpoint: ${task.destinationEndpoint}", style = MaterialTheme.typography.bodySmall)
                    Text("Attempts: ${task.attemptCount} (Created: $createdStr)", style = MaterialTheme.typography.bodySmall)

                    if (!task.errorMessage.isNullOrBlank()) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Error Response Diagnostic:", fontWeight = FontWeight.Bold, color = ErrorRed, style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(task.errorMessage ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        }
                    }

                    if (associatedSms != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("Associated SMS:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text("From: ${associatedSms!!.sender} • ${if (associatedSms!!.simSlotIndex >= 0) "SIM " + (associatedSms!!.simSlotIndex + 1) else "Unknown"}", style = MaterialTheme.typography.bodySmall)
                                Spacer(modifier = Modifier.height(4.dp))
                                Text("Message: ${associatedSms!!.rawBody}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("SMS Forwarder Log", logSummary)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(context, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Copy Log")
                }
            },
            dismissButton = {
                TextButton(onClick = { selectedTaskForDetails = null }) {
                    Text("Close")
                }
            }
        )
    }
}

@Composable
fun DeliveryTaskCard(
    task: DeliveryTask,
    onClick: () -> Unit,
    onRetry: () -> Unit
) {
    val dateFormat = SimpleDateFormat("MMM dd, HH:mm:ss", Locale.getDefault())
    val timestamp = task.lastAttemptAt ?: task.createdAt
    val formattedTime = dateFormat.format(Date(timestamp))

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val statusColor = when (task.status) {
                        TaskStatus.SUCCESS -> SuccessGreen
                        TaskStatus.PENDING, TaskStatus.IN_PROGRESS -> WarningAmber
                        TaskStatus.FAILED_RETRYABLE, TaskStatus.FAILED_EXHAUSTED -> ErrorRed
                    }
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = task.status.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = statusColor
                    )
                }

                Text(
                    text = formattedTime,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${task.destinationType.name} -> ${task.destinationEndpoint}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Attempts: ${task.attemptCount} • Tap for detailed logs",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)
                )
            }

            if (task.errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Error: ${task.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorRed
                )
            }

            if (task.status == TaskStatus.FAILED_RETRYABLE || task.status == TaskStatus.FAILED_EXHAUSTED) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Retry Now")
                    }
                }
            }
        }
    }
}

