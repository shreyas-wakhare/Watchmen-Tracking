package com.watchmen.tracker

import android.Manifest
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.media.MediaRecorder
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody  // ✅ ADD THIS IMPORT
import org.json.JSONObject
import java.io.File

class GeofenceActionHandler(private val context: Context) {

    private val devicePolicyManager = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
    private val client = OkHttpClient()
    private var mediaRecorder: MediaRecorder? = null
    private var isRecording = false

    companion object {
        private const val TAG = "GeofenceActions"
        private const val VIDEO_DURATION = 30000L // 30 seconds
    }

    /**
     * Execute all configured actions when geofence is violated
     */
    suspend fun executeViolationActions(
        location: Location,
        deviceId: String,
        violationType: String
    ) = withContext(Dispatchers.IO) {
        Log.e(TAG, "🚨 GEOFENCE VIOLATION! Executing emergency actions...")

        // Execute actions in parallel
        val actions = listOf(
            async { autoLockScreen() },
            async { autoCapturePhoto() },
            async { sendPanicSignal(location, deviceId, violationType) },
            async { startEmergencyVideoRecording() }
        )

        // Wait for all actions to complete
        actions.awaitAll()

        Log.i(TAG, "✅ All emergency actions executed")
    }

    // ===== ACTION 1: AUTO-LOCK SCREEN =====
    private fun autoLockScreen(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Lock screen using Device Policy Manager (requires admin)
                // ✅ FIXED: Correct ComponentName syntax
                val adminComponent = ComponentName(context, WatchmenDeviceAdminReceiver::class.java)
                if (devicePolicyManager.isAdminActive(adminComponent)) {
                    devicePolicyManager.lockNow()
                    Log.i(TAG, "🔒 Screen locked via Device Admin")
                    true
                } else {
                    // Fallback: Turn off screen
                    turnOffScreen()
                    false
                }
            } else {
                turnOffScreen()
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to lock screen: ${e.message}")
            false
        }
    }

    private fun turnOffScreen() {
        try {
            // Turn off screen by acquiring and releasing wake lock
            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                "Watchmen::ScreenOff"
            )
            wakeLock.acquire(1000)
            wakeLock.release()
            Log.i(TAG, "📴 Screen turned off")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to turn off screen: ${e.message}")
        }
    }

    // ===== ACTION 2: AUTO-CAPTURE PHOTO =====
    private suspend fun autoCapturePhoto(): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Camera permission not granted")
            return@withContext null
        }

        try {
            Log.i(TAG, "📸 Auto-capturing photo...")

            // Trigger camera capture via broadcast
            val captureIntent = Intent("com.watchmen.tracker.AUTO_CAPTURE_PHOTO")
            captureIntent.setPackage(context.packageName)
            captureIntent.putExtra("reason", "geofence_violation")
            captureIntent.putExtra("timestamp", System.currentTimeMillis())
            context.sendBroadcast(captureIntent)

            Log.i(TAG, "✅ Photo capture triggered")
            "Photo capture initiated"
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to capture photo: ${e.message}")
            null
        }
    }

    // ===== ACTION 3: SEND PANIC SIGNAL =====
    private suspend fun sendPanicSignal(
        location: Location,
        deviceId: String,
        violationType: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("device_id", deviceId)
                put("alert_type", "PANIC_GEOFENCE_VIOLATION")
                put("violation_type", violationType)
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("timestamp", System.currentTimeMillis())
                put("battery", getBatteryLevel(context))
                put("accuracy", location.accuracy)
                put("priority", "CRITICAL")
            }.toString()

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(BackendEndpointManager.getHttpUrl("/alert"))
                .post(body)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.i(TAG, "🚨 PANIC SIGNAL SENT SUCCESSFULLY")
                true
            } else {
                Log.e(TAG, "❌ Panic signal failed: ${response.code}")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send panic signal: ${e.message}")
            false
        }
    }

    // ===== ACTION 4: START EMERGENCY VIDEO RECORDING =====
    private suspend fun startEmergencyVideoRecording(): String? = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Camera permission not granted for video")
            return@withContext null
        }

        try {
            if (isRecording) {
                Log.w(TAG, "⚠️ Already recording video")
                return@withContext null
            }

            val videoFile = File(context.getExternalFilesDir(null), "emergency_${System.currentTimeMillis()}.mp4")

            Log.i(TAG, "🎥 Starting emergency video recording...")

            mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setVideoSource(MediaRecorder.VideoSource.CAMERA)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setVideoSize(640, 480)
                setVideoFrameRate(30)
                setOutputFile(videoFile.absolutePath)
                prepare()
                start()
            }

            isRecording = true
            Log.i(TAG, "✅ Emergency video recording started")

            // Stop recording after duration
            GlobalScope.launch(Dispatchers.IO) {
                delay(VIDEO_DURATION)
                stopVideoRecording(videoFile)
            }

            videoFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start video recording: ${e.message}", e)
            null
        }
    }

    private suspend fun stopVideoRecording(videoFile: File) = withContext(Dispatchers.IO) {
        try {
            if (isRecording) {
                mediaRecorder?.apply {
                    stop()
                    reset()
                    release()
                }
                mediaRecorder = null
                isRecording = false

                Log.i(TAG, "✅ Video recording stopped: ${videoFile.name}")

                // Upload video to server
                uploadVideo(videoFile)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to stop video: ${e.message}")
        }
    }

    private suspend fun uploadVideo(videoFile: File) = withContext(Dispatchers.IO) {
        try {
            Log.i(TAG, "📤 Uploading emergency video...")

            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "video",
                    videoFile.name,
                    videoFile.asRequestBody("video/mp4".toMediaType())
                )
                .addFormDataPart("device_id", getDeviceId())
                .addFormDataPart("reason", "geofence_violation")
                .addFormDataPart("timestamp", System.currentTimeMillis().toString())
                .build()

            val request = Request.Builder()
                .url(BackendEndpointManager.getHttpUrl("/upload/video"))
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Log.i(TAG, "✅ Video uploaded successfully")
                videoFile.delete()
            } else {
                Log.e(TAG, "❌ Video upload failed: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to upload video: ${e.message}")
        }
    }

    private fun getBatteryLevel(context: Context): Float {
        val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    private fun getDeviceId(): String {
        val prefs = context.getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_id", "UNKNOWN") ?: "UNKNOWN"
    }
}
