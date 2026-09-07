package com.watchmen.tracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Automatically starts TrackingService when device boots
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_REBOOT
            )
        ) {
            Log.i("Watchmen", "📱 BOOT COMPLETED - Starting TrackingService...")

            val serviceIntent = Intent(context, TrackingService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("Watchmen", "✅ TrackingService started after boot")
            } catch (e: Exception) {
                Log.e("Watchmen", "❌ Failed to start service after boot: ${e.message}")
            }
        }
    }
}
