package com.smsforwarder.app.engine.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.smsforwarder.app.R
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.engine.queue.QueueDrainer
import com.smsforwarder.app.engine.worker.DeliveryWorker
import com.smsforwarder.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Persistent Foreground Service maintaining continuous process priority.
 * Complies with Android 14/15 foregroundServiceType="specialUse".
 * Listens to active queue changes and keeps ongoing status notifications updated.
 */
class SmsForwarderService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannel(this)

        val initialNotification = NotificationHelper.buildServiceNotification(
            context = this,
            statusText = "Active • Monitoring incoming SMS"
        )

        // Android 14+ requires foreground service type parameter in startForeground
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        observeQueueMetrics()

        // Immediately drain any pending/retry tasks on service start
        serviceScope.launch {
            QueueDrainer.drainQueue(this@SmsForwarderService)
        }

        // Periodic coroutine ticker (every 30 seconds) to ensure continuous queue processing independent of JobScheduler
        serviceScope.launch {
            while (isActive) {
                delay(30_000L)
                QueueDrainer.drainQueue(this@SmsForwarderService)
            }
        }

        Timber.i("SmsForwarderService created and started in foreground with active QueueDrainer loop")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_NOTIFY_NEW_TASKS) {
            Timber.d("SmsForwarderService received ACTION_NOTIFY_NEW_TASKS - triggering immediate queue drain")
            serviceScope.launch {
                QueueDrainer.drainQueue(this@SmsForwarderService)
            }
        }
        return START_STICKY
    }

    private fun observeQueueMetrics() {
        serviceScope.launch {
            val app = SmsForwarderApp.instance
            app.deliveryQueueRepository.getAllTasksFlow().collectLatest { tasks ->
                val successCount = tasks.count { it.status == com.smsforwarder.app.domain.model.TaskStatus.SUCCESS }
                val pendingCount = tasks.count { it.status == com.smsforwarder.app.domain.model.TaskStatus.PENDING }
                val failedCount = tasks.count { 
                    it.status == com.smsforwarder.app.domain.model.TaskStatus.FAILED_RETRYABLE || 
                    it.status == com.smsforwarder.app.domain.model.TaskStatus.FAILED_EXHAUSTED 
                }

                val statusText = "Forwarded: $successCount | Pending: $pendingCount | Failed: $failedCount"
                val updatedNotification = NotificationHelper.buildServiceNotification(
                    context = this@SmsForwarderService,
                    statusText = statusText
                )

                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.notify(NOTIFICATION_ID, updatedNotification)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Timber.i("SmsForwarderService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_NOTIFY_NEW_TASKS = "com.smsforwarder.app.action.NOTIFY_NEW_TASKS"

        fun start(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java)
            context.stopService(intent)
        }

        fun notifyNewTasks(context: Context) {
            val intent = Intent(context, SmsForwarderService::class.java).apply {
                action = ACTION_NOTIFY_NEW_TASKS
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send NOTIFY_NEW_TASKS to SmsForwarderService")
            }
        }
    }
}

object NotificationHelper {
    const val CHANNEL_ID = "sms_forwarder_foreground_service"

    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = context.getString(R.string.channel_service_name)
            val desc = context.getString(R.string.channel_service_desc)
            val channel = NotificationChannel(
                CHANNEL_ID,
                name,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = desc
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun buildServiceNotification(context: Context, statusText: String): Notification {
        val launchIntent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("SMS Forwarder Active")
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
    }
}
