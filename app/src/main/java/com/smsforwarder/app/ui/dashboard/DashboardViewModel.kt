package com.smsforwarder.app.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.smsforwarder.app.domain.model.DeliveryTask
import com.smsforwarder.app.domain.model.ForwardingRule
import com.smsforwarder.app.domain.model.SmsMessage
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import com.smsforwarder.app.domain.repository.SmsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper


data class DashboardUiState(
    val isForwardingServiceActive: Boolean = true,
    val totalReceivedCount: Int = 0,
    val totalSuccessCount: Int = 0,
    val totalFailedCount: Int = 0,
    val activeRules: List<ForwardingRule> = emptyList(),
    val recentSms: List<SmsMessage> = emptyList(),
    val recentTasks: List<DeliveryTask> = emptyList(),
    // Onboarding & Permissions Checklist
    val isSmsPermissionGranted: Boolean = false,
    val isPhoneStatePermissionGranted: Boolean = false,
    val isNotificationPermissionGranted: Boolean = false,
    val isBatteryExempt: Boolean = false,
    val isGuideCollapsed: Boolean = false
)

class DashboardViewModel(
    private val smsRepository: SmsRepository,
    private val ruleRepository: ForwardingRuleRepository,
    private val queueRepository: DeliveryQueueRepository
) : ViewModel() {

    private val prefs = SmsForwarderApp.instance.appPreferences
    private val _isServiceActive = MutableStateFlow(prefs.isServiceRunning)
    private val _isSmsPermission = MutableStateFlow(false)
    private val _isPhoneStatePermission = MutableStateFlow(false)
    private val _isNotificationPermission = MutableStateFlow(false)
    private val _isBatteryExempt = MutableStateFlow(false)
    private val _isGuideCollapsed = MutableStateFlow(false)

    init {
        refreshSystemStatus(SmsForwarderApp.instance)
    }

    val uiState: StateFlow<DashboardUiState> = combine(
        _isServiceActive,
        smsRepository.getAllSmsFlow(),
        ruleRepository.getAllRulesFlow(),
        queueRepository.getAllTasksFlow(),
        combine(_isSmsPermission, _isPhoneStatePermission, _isNotificationPermission, _isBatteryExempt, _isGuideCollapsed) { s, p, n, b, g ->
            ChecklistFlags(s, p, n, b, g)
        }
    ) { serviceActive, smsList, rulesList, tasksList, flags ->
        val successCount = tasksList.count { it.status == com.smsforwarder.app.domain.model.TaskStatus.SUCCESS }
        val failedCount = tasksList.count { 
            it.status == com.smsforwarder.app.domain.model.TaskStatus.FAILED_RETRYABLE || 
            it.status == com.smsforwarder.app.domain.model.TaskStatus.FAILED_EXHAUSTED 
        }
        val activeRules = rulesList.filter { it.isEnabled }
        val recentSms = smsList.take(5)
        val recentTasks = tasksList.take(5)

        DashboardUiState(
            isForwardingServiceActive = serviceActive,
            totalReceivedCount = smsList.size,
            totalSuccessCount = successCount,
            totalFailedCount = failedCount,
            activeRules = activeRules,
            recentSms = recentSms,
            recentTasks = recentTasks,
            isSmsPermissionGranted = flags.sms,
            isPhoneStatePermissionGranted = flags.phoneState,
            isNotificationPermissionGranted = flags.notifications,
            isBatteryExempt = flags.batteryExempt,
            isGuideCollapsed = flags.guideCollapsed
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardUiState()
    )

    fun refreshSystemStatus(context: Context) {
        val sms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val phone = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        val battery = BatteryOptimizationHelper.isIgnoringBatteryOptimizations(context)

        _isSmsPermission.value = sms
        _isPhoneStatePermission.value = phone
        _isNotificationPermission.value = notif
        _isBatteryExempt.value = battery
        _isServiceActive.value = prefs.isServiceRunning
    }

    fun toggleMasterForwarding(enabled: Boolean) {
        prefs.isServiceRunning = enabled
        _isServiceActive.value = enabled
        if (enabled) {
            com.smsforwarder.app.engine.service.SmsForwarderService.start(SmsForwarderApp.instance)
        } else {
            com.smsforwarder.app.engine.service.SmsForwarderService.stop(SmsForwarderApp.instance)
        }
    }

    fun toggleGuideCollapse() {
        _isGuideCollapsed.value = !_isGuideCollapsed.value
    }

    private data class ChecklistFlags(
        val sms: Boolean,
        val phoneState: Boolean,
        val notifications: Boolean,
        val batteryExempt: Boolean,
        val guideCollapsed: Boolean
    )
}
