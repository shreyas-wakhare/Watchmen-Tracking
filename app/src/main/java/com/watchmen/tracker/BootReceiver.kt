package com.watchmen.tracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchmen.tracker.auth.AuthManager

/**
 * Automatically starts TrackingService when device boots, ONLY IF user is authenticated
 * and location permissions have been granted.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in listOf(
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                Intent.ACTION_REBOOT
            )
        ) {
            Log.i("Watchmen", "📱 BOOT COMPLETED intent received.")

            AuthManager.init(context)

            val isUserLoggedIn = AuthManager.isLoggedIn(context)
            val hasLocationPermission = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!isUserLoggedIn) {
                Log.i("Watchmen", "ℹ️ Skipping boot auto-start: User is not authenticated.")
                return
            }

            if (!hasLocationPermission) {
                Log.w("Watchmen", "⚠️ Skipping boot auto-start: Location permission is missing.")
                return
            }

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
