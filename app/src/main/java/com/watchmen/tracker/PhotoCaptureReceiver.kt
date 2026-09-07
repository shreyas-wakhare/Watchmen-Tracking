package com.watchmen.tracker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

class PhotoCaptureReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        context?.let {
            showPhotoNotification(it)
            announceCheckIn(it)  // ✅ ADD this line

        }
    }
    private fun announceCheckIn(context: Context) {
        val prefs = context.getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        val language = prefs.getString("app_language", "en") ?: "en"

        val tts = MultilingualTTS(context)
        tts.initialize(onSuccess = {
            tts.speak(
                context.getString(R.string.hourly_checkin_reminder),
                language,
                urgent = false
            )
            // Shutdown after 5 seconds
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                tts.shutdown()
            }, 5000)
        })
    }
    private fun showPhotoNotification(context: Context) {
        val channelId = "hourly_photo_channel"
        val manager = context.getSystemService(NotificationManager::class.java)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Hourly Check-In",
                NotificationManager.IMPORTANCE_HIGH
            )
            manager.createNotificationChannel(channel)
        }

        val photoIntent = Intent(context, CameraCaptureActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context, 0, photoIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("📸 Hourly Check-In Required")
            .setContentText("Tap to capture your location photo")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setFullScreenIntent(pendingIntent, true)
            .build()

        manager.notify(1002, notification)
    }
}
