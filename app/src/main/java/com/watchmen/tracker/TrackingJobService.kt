package com.watchmen.tracker

import android.Manifest
import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.watchmen.tracker.auth.AuthManager

class TrackingJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.w("Watchmen", "🔄 JobService triggered.")

        AuthManager.init(applicationContext)

        val isUserLoggedIn = AuthManager.isLoggedIn(applicationContext)
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            applicationContext,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!isUserLoggedIn || !hasLocationPermission) {
            Log.i("Watchmen", "ℹ️ JobService skipping TrackingService start: LoggedIn=$isUserLoggedIn, LocationPerm=$hasLocationPermission")
            jobFinished(params, false)
            return false
        }

        val intent = Intent(applicationContext, TrackingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i("Watchmen", "✅ JobService: TrackingService restarted")
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ JobService failed to start service: ${e.message}")
        }

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true // Reschedule
    }
}
