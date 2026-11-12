package com.smsforwarder.app.engine.reliability

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import com.smsforwarder.app.SmsForwarderApp
import com.smsforwarder.app.engine.queue.QueueManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors system network connectivity states in real time.
 * When internet connectivity transitions to available, it triggers immediate
 * delivery queue flushing to clear pending and retryable delivery tasks
 * without waiting for periodic WorkManager intervals.
 */
class NetworkConnectivityMonitor(
    private val context: Context
) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isRegistered = AtomicBoolean(false)
    private val wasConnected = AtomicBoolean(false)
    private var debounceJob: Job? = null

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            super.onAvailable(network)
            val isFirstConnection = !wasConnected.getAndSet(true)
            Timber.i("Network available (firstConnection=$isFirstConnection)")

            // Debounce rapid link flaps
            debounceJob?.cancel()
            debounceJob = scope.launch {
                delay(DEBOUNCE_INTERVAL_MS)
                onNetworkRestored()
            }
        }

        override fun onLost(network: Network) {
            super.onLost(network)
            wasConnected.set(false)
            Timber.w("Network connectivity lost")
        }

        override fun onCapabilitiesChanged(
            network: Network,
            networkCapabilities: NetworkCapabilities
        ) {
            super.onCapabilitiesChanged(network, networkCapabilities)
            val hasInternet = networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (hasInternet && !wasConnected.getAndSet(true)) {
                Timber.i("Network internet validated, triggering queue flush")
                debounceJob?.cancel()
                debounceJob = scope.launch {
                    delay(DEBOUNCE_INTERVAL_MS)
                    onNetworkRestored()
                }
            }
        }
    }

    /**
     * Registers the network callback. Safe to call multiple times.
     */
    fun startMonitoring() {
        if (connectivityManager == null) {
            Timber.w("ConnectivityManager unavailable, cannot monitor network")
            return
        }

        if (isRegistered.compareAndSet(false, true)) {
            try {
                val request = NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build()
                connectivityManager.registerNetworkCallback(request, networkCallback)
                Timber.i("NetworkConnectivityMonitor registered successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to register NetworkCallback")
                isRegistered.set(false)
            }
        }
    }

    /**
     * Unregisters the network callback.
     */
    fun stopMonitoring() {
        if (connectivityManager == null) return
        if (isRegistered.compareAndSet(true, false)) {
            try {
                connectivityManager.unregisterNetworkCallback(networkCallback)
                Timber.i("NetworkConnectivityMonitor unregistered successfully")
            } catch (e: Exception) {
                Timber.e(e, "Failed to unregister NetworkCallback")
            }
        }
        debounceJob?.cancel()
    }

    /**
     * Inspects current network capabilities synchronously.
     */
    fun isConnected(): Boolean {
        if (connectivityManager == null) return false
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun onNetworkRestored() {
        try {
            val app = SmsForwarderApp.instance
            val pendingTasks = app.deliveryQueueRepository.getPendingTasks()
            val dueTasks = app.deliveryQueueRepository.getDueRetryTasks(System.currentTimeMillis())
            val count = pendingTasks.size + dueTasks.size

            if (count > 0) {
                Timber.i("Network restored: Found %d pending/due tasks. Flushing queue immediately.", count)
                QueueManager.flushQueue(context)
            } else {
                Timber.d("Network restored: No pending delivery tasks found.")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking queue on network restoration")
            // Fallback flush
            QueueManager.flushQueue(context)
        }
    }

    companion object {
        private const val DEBOUNCE_INTERVAL_MS = 1000L
    }
}
