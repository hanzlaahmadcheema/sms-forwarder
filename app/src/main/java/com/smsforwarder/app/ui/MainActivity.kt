package com.smsforwarder.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.rememberNavController
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.engine.service.SmsForwarderService
import com.smsforwarder.app.ui.dashboard.DashboardViewModel
import com.smsforwarder.app.ui.history.HistoryViewModel
import com.smsforwarder.app.ui.navigation.AppNavigation
import com.smsforwarder.app.ui.rules.RulesViewModel
import com.smsforwarder.app.ui.settings.SettingsViewModel
import com.smsforwarder.app.ui.theme.SmsForwarderTheme
import timber.log.Timber

class MainActivity : ComponentActivity() {

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val smsGranted = permissions[Manifest.permission.RECEIVE_SMS] == true
        Timber.i("Permission request result: RECEIVE_SMS granted = %b", smsGranted)
        if (smsGranted) {
            SmsForwarderService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request SMS & Notification permissions on launch
        checkAndRequestPermissions()

        val app = SmsForwarderApp.instance

        val dashboardViewModel by viewModels<DashboardViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return DashboardViewModel(
                        smsRepository = app.smsRepository,
                        ruleRepository = app.forwardingRuleRepository,
                        queueRepository = app.deliveryQueueRepository
                    ) as T
                }
            }
        }

        val rulesViewModel by viewModels<RulesViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return RulesViewModel(
                        ruleRepository = app.forwardingRuleRepository,
                        vaultRepository = app.secureVaultRepository
                    ) as T
                }
            }
        }

        val historyViewModel by viewModels<HistoryViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return HistoryViewModel(
                        queueRepository = app.deliveryQueueRepository,
                        smsRepository = app.smsRepository
                    ) as T
                }
            }
        }

        val settingsViewModel by viewModels<SettingsViewModel> {
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    @Suppress("UNCHECKED_CAST")
                    return SettingsViewModel(
                        smsRepository = app.smsRepository,
                        ruleRepository = app.forwardingRuleRepository,
                        queueRepository = app.deliveryQueueRepository,
                        vaultRepository = app.secureVaultRepository
                    ) as T
                }
            }
        }

        setContent {
            SmsForwarderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()
                    AppNavigation(
                        navController = navController,
                        dashboardViewModel = dashboardViewModel,
                        rulesViewModel = rulesViewModel,
                        historyViewModel = historyViewModel,
                        settingsViewModel = settingsViewModel
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_PHONE_STATE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            SmsForwarderService.start(this)
        }
    }
}
