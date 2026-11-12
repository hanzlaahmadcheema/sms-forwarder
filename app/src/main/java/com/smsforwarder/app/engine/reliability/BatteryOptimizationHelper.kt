package com.smsforwarder.app.engine.reliability

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.smsforwarder.app.data.local.prefs.AppPreferences
import com.smsforwarder.app.engine.service.SmsForwarderService
import timber.log.Timber
import java.util.Locale

/**
 * Helper to audit and manage Android Doze and aggressive OEM battery optimizations
 * across standard AOSP, Xiaomi (MIUI/HyperOS), Samsung (OneUI), Huawei (EMUI/HarmonyOS),
 * OnePlus (OxygenOS), and ColorOS/FuntouchOS devices.
 */
object BatteryOptimizationHelper {

    /**
     * Checks if the app is currently whitelisted from system-level Doze battery optimizations.
     */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        return try {
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        } catch (e: Exception) {
            Timber.e(e, "Failed to check isIgnoringBatteryOptimizations")
            false
        }
    }

    /**
     * Generates standard Android battery optimization exemption intent.
     */
    @SuppressLint("BatteryLife")
    fun createIgnoreBatteryOptimizationIntent(context: Context): Intent {
        val packageName = context.packageName
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return if (isIntentAvailable(context, intent)) {
            intent
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        }
    }

    /**
     * Attempts to open the best-matching battery optimization settings for the current device,
     * prioritizing manufacturer OEM powerkeeper/autostart panels before falling back to AOSP.
     */
    fun openBatterySettings(context: Context): Boolean {
        // Try OEM specific intents first
        val oemIntents = getOemBatterySettingsIntents(context)
        for (intent in oemIntents) {
            if (isIntentAvailable(context, intent)) {
                try {
                    context.startActivity(intent)
                    Timber.i("Launched OEM battery management activity: %s", intent.component)
                    return true
                } catch (e: Exception) {
                    Timber.w(e, "OEM battery activity launch failed: %s", intent.component)
                }
            }
        }

        // Fallback to standard AOSP battery optimization exemption dialog
        return try {
            val standardIntent = createIgnoreBatteryOptimizationIntent(context)
            context.startActivity(standardIntent)
            Timber.i("Launched standard battery optimization settings")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to launch standard battery optimization settings")
            // Ultimate fallback: App details settings
            try {
                val appDetailsIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(appDetailsIntent)
                true
            } catch (fallbackError: Exception) {
                Timber.e(fallbackError, "Failed to launch application details settings")
                false
            }
        }
    }

    /**
     * Builds list of OEM-specific battery management and autostart intents.
     */
    fun getOemBatterySettingsIntents(context: Context): List<Intent> {
        val intents = mutableListOf<Intent>()
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)

        when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                // Xiaomi MIUI / HyperOS
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    putExtra("package_name", context.packageName)
                    putExtra("package_label", "SMS Forwarder")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                intents.add(Intent("miui.intent.action.OP_AUTO_START").apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            manufacturer.contains("samsung") -> {
                // Samsung OneUI
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                // Huawei EMUI / HarmonyOS
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
                intents.add(Intent().apply {
                    component = ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            manufacturer.contains("oneplus") -> {
                // OnePlus OxygenOS
                intents.add(Intent().apply {
                    component = ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            manufacturer.contains("oppo") || manufacturer.contains("realme") -> {
                // ColorOS
                intents.add(Intent().apply {
                    component = ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }

            manufacturer.contains("vivo") -> {
                // Vivo FuntouchOS / OriginOS
                intents.add(Intent().apply {
                    component = ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                })
            }
        }

        return intents
    }

    /**
     * Watchdog mechanism: Checks if the foreground service is configured to run,
     * and restarts it if the system killed the host process.
     */
    fun ensureServiceRunning(context: Context) {
        val prefs = AppPreferences(context)
        if (prefs.isServiceRunning) {
            try {
                SmsForwarderService.start(context)
                Timber.d("Watchdog: Ensured SmsForwarderService is running")
            } catch (e: Exception) {
                Timber.e(e, "Watchdog: Failed to restart SmsForwarderService")
            }
        }
    }

    private fun isIntentAvailable(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager
        val resolveInfo = pm.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY
        )
        return resolveInfo.isNotEmpty()
    }
}
