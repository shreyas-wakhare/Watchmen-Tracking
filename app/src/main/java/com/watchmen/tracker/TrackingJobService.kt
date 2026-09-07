package com.watchmen.tracker

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import android.os.Build
import android.util.Log

class TrackingJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.w("Watchmen", "🔄 JobService: Ensuring TrackingService is alive...")

        val intent = Intent(applicationContext, TrackingService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i("Watchmen", "✅ JobService: TrackingService restarted")
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ JobService failed: ${e.message}")
        }

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        return true // Reschedule
    }
}
