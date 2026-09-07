package com.watchmen.tracker

import android.app.*
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

class HourlyPhotoWorker(appContext: Context, params: WorkerParameters)
    : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        showNotification()
        return Result.success()
    }

    private fun showNotification() {
        val ctx = applicationContext
        val channelId = "hourly_photo_channel"
        val manager = ctx.getSystemService(NotificationManager::class.java)

        val channel = NotificationChannel(
            channelId,
            "Hourly Photo Reminder",
            NotificationManager.IMPORTANCE_HIGH
        )
        manager.createNotificationChannel(channel)

        val intent = Intent(ctx, CameraCaptureActivity::class.java).apply {
            putExtra("HOURLY_CHECKIN", true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            ctx,
            1002,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notif = NotificationCompat.Builder(ctx, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("📸 Hourly Check-In")
            .setContentText("Tap to capture your hourly work photo.")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(ctx).notify(1002, notif)
    }
}
