package com.watchmen.tracker

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log

/**
 * Dedicated controller for remote emergency alarm / siren and vibration.
 * Ensures single MediaPlayer instance, idempotence, correct audio attributes,
 * and reliable cleanup without resource leaks.
 */
class SirenController(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    private var isPlaying: Boolean = false

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? android.os.VibratorManager
            vibratorManager?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    /**
     * Start audible siren and repeating vibration.
     * Idempotent: safe to call repeatedly without creating multiple MediaPlayer instances.
     */
    @Synchronized
    fun startSiren(): Boolean {
        if (isPlaying) {
            Log.i("SirenController", "🚨 Siren already active (idempotent call)")
            return true
        }

        return try {
            // Set alarm volume to maximum for emergency audibility
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.let { am ->
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                am.setStreamVolume(AudioManager.STREAM_ALARM, maxVol, 0)
            }

            // Retrieve safe device alarm sound or fallback to ringtone/notification
            val alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alertUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            isPlaying = true

            // Continuous repeating emergency vibration pattern: delay 0ms, vibrate 800ms, sleep 400ms...
            val pattern = longArrayOf(0, 800, 400, 800, 400)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }

            Log.i("SirenController", "🚨 Siren and vibration activated successfully")
            true
        } catch (e: Exception) {
            Log.e("SirenController", "❌ Failed to start siren: ${e.message}", e)
            try {
                mediaPlayer?.release()
            } catch (_: Exception) {}
            mediaPlayer = null
            isPlaying = false
            false
        }
    }

    /**
     * Stop siren playback and cancel vibration.
     * Idempotent: safe to call even if siren is already stopped.
     */
    @Synchronized
    fun stopSiren(): Boolean {
        return try {
            mediaPlayer?.apply {
                if (isPlaying) {
                    try {
                        stop()
                    } catch (_: Exception) {}
                }
                release()
            }
            mediaPlayer = null
            isPlaying = false
            vibrator?.cancel()
            Log.i("SirenController", "🛑 Siren and vibration stopped successfully")
            true
        } catch (e: Exception) {
            Log.e("SirenController", "❌ Failed to stop siren: ${e.message}", e)
            false
        }
    }

    @Synchronized
    fun isSirenActive(): Boolean = isPlaying
}
