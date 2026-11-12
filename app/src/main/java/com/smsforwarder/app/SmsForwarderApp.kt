package com.smsforwarder.app

import android.app.Application
import com.smsforwarder.app.core.util.SecurityLogTree
import com.smsforwarder.app.data.local.AppDatabase
import com.smsforwarder.app.data.repository.DeliveryQueueRepositoryImpl
import com.smsforwarder.app.data.repository.ForwardingRuleRepositoryImpl
import com.smsforwarder.app.data.repository.SecureVaultRepositoryImpl
import com.smsforwarder.app.data.repository.SmsRepositoryImpl
import com.smsforwarder.app.data.security.SecureVault
import com.smsforwarder.app.domain.repository.DeliveryQueueRepository
import com.smsforwarder.app.domain.repository.ForwardingRuleRepository
import com.smsforwarder.app.domain.repository.SecureVaultRepository
import com.smsforwarder.app.domain.repository.SmsRepository
import com.smsforwarder.app.domain.usecase.EnqueueForwardingUseCase
import com.smsforwarder.app.domain.usecase.FilterSmsUseCase
import com.smsforwarder.app.domain.usecase.ReceiveSmsUseCase
import com.smsforwarder.app.engine.service.NotificationHelper
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application entry point for SMS Forwarder.
 * Initializes SecurityLogTree with logcat redaction and provides service locator dependencies.
 */
class SmsForwarderApp : Application() {

    lateinit var database: AppDatabase private set
    lateinit var secureVault: SecureVault private set
    lateinit var smsRepository: SmsRepository private set
    lateinit var forwardingRuleRepository: ForwardingRuleRepository private set
    lateinit var deliveryQueueRepository: DeliveryQueueRepository private set
    lateinit var secureVaultRepository: SecureVaultRepository private set

    // Phase 4 Engine Use Cases
    lateinit var receiveSmsUseCase: ReceiveSmsUseCase private set
    lateinit var filterSmsUseCase: FilterSmsUseCase private set
    lateinit var enqueueForwardingUseCase: EnqueueForwardingUseCase private set

    // Phase 5 Protocol Dispatchers
    lateinit var webhookDispatcher: com.smsforwarder.app.engine.dispatcher.WebhookDispatcher private set
    lateinit var telegramDispatcher: com.smsforwarder.app.engine.dispatcher.TelegramDispatcher private set
    lateinit var emailDispatcher: com.smsforwarder.app.engine.dispatcher.EmailDispatcher private set
    lateinit var whatsAppAgentDispatcher: com.smsforwarder.app.engine.dispatcher.WhatsAppAgentDispatcher private set

    // Phase 6 Reliability & Engine
    lateinit var appPreferences: com.smsforwarder.app.data.local.prefs.AppPreferences private set
    lateinit var networkConnectivityMonitor: com.smsforwarder.app.engine.reliability.NetworkConnectivityMonitor private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Enforce production sanitized logging
        Timber.plant(SecurityLogTree())
        Timber.i("SmsForwarderApp initialized with SecurityLogTree")

        // Initialize Notification Channels
        NotificationHelper.createNotificationChannel(this)

        // Initialize Preferences & Local Storage
        appPreferences = com.smsforwarder.app.data.local.prefs.AppPreferences(this)
        database = AppDatabase.getInstance(this)
        secureVault = SecureVault(this)

        smsRepository = SmsRepositoryImpl(database.smsRecordDao())
        forwardingRuleRepository = ForwardingRuleRepositoryImpl(database.forwardingRuleDao())
        deliveryQueueRepository = DeliveryQueueRepositoryImpl(database.deliveryTaskDao())
        secureVaultRepository = SecureVaultRepositoryImpl(secureVault)

        // Initialize Phase 4 Use Cases
        receiveSmsUseCase = ReceiveSmsUseCase(smsRepository)
        filterSmsUseCase = FilterSmsUseCase(forwardingRuleRepository)
        enqueueForwardingUseCase = EnqueueForwardingUseCase(deliveryQueueRepository)

        // Initialize Phase 5 Protocol Dispatchers
        webhookDispatcher = com.smsforwarder.app.engine.dispatcher.WebhookDispatcher(secureVault)
        telegramDispatcher = com.smsforwarder.app.engine.dispatcher.TelegramDispatcher(secureVault)
        emailDispatcher = com.smsforwarder.app.engine.dispatcher.EmailDispatcher(secureVault)
        whatsAppAgentDispatcher = com.smsforwarder.app.engine.dispatcher.WhatsAppAgentDispatcher(secureVault)

        // Initialize Phase 6 Reliability Components
        networkConnectivityMonitor = com.smsforwarder.app.engine.reliability.NetworkConnectivityMonitor(this)
        networkConnectivityMonitor.startMonitoring()

        // Schedule WorkManager Background Execution
        com.smsforwarder.app.engine.queue.QueueManager.schedulePeriodicWork(this)
        com.smsforwarder.app.engine.worker.RetentionPruneWorker.scheduleDailyPruning(this)

        // Watchdog check for persistent foreground service
        com.smsforwarder.app.engine.reliability.BatteryOptimizationHelper.ensureServiceRunning(this)

        // Drain any pending or deferred tasks on app launch
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            com.smsforwarder.app.engine.queue.QueueDrainer.drainQueue(this@SmsForwarderApp)
        }
    }

    companion object {
        lateinit var instance: SmsForwarderApp
            private set
    }
}
