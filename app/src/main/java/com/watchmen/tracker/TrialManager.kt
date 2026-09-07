package com.watchmen.tracker

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class TrialManager(private val context: Context, private val stringProvider: StringProvider) {
    var currentTrial: Int = 1
        private set
    // FIX: maxTrials is now public
    val maxTrials: Int = 3

    val hasRemainingTrials: Boolean
        get() = currentTrial < maxTrials

    fun registerFailedTrial() {
        if (currentTrial <= maxTrials) {
            currentTrial++
        }
    }

    fun isFinalTrial(): Boolean = currentTrial == maxTrials

    fun reset() {
        currentTrial = 1
    }

    // Reports now map LivenessReason codes to strings
    suspend fun reportFailureToSupervisor(reasons: List<String>, confidence: Float) {
        val deviceId = getTrackerDeviceId()
        val failureDetails = "Device: $deviceId, Trial: ${currentTrial - 1}, Confidence: ${"%.2f".format(confidence)}, Reasons: ${reasons.joinToString("; ")}"
        Log.w("TrialManager", "Reporting Failure: $failureDetails")

        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        val jsonBody = """
            {
                "deviceId": "$deviceId",
                "attemptNumber": ${currentTrial - 1},
                "confidence": $confidence,
                "reasons": "${reasons.joinToString("; ")}",
                "timestamp": ${System.currentTimeMillis()}
            }
        """.trimIndent().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

        val request = Request.Builder()
            .url(BackendEndpointManager.getHttpUrl("/reportFailure"))
            .post(jsonBody)
            .build()

        withContext(Dispatchers.IO) {
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.e("TrialManager", "Supervisor report failed: ${response.code}")
                    } else {
                        Log.i("TrialManager", "Supervisor report successful.")
                    }
                }
            } catch (e: IOException) {
                Log.e("TrialManager", "Supervisor report network error: ${e.message}")
            }
        }
    }

    private fun getTrackerDeviceId(): String {
        val prefs = context.getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_token", "UNKNOWN") ?: "UNKNOWN"
    }
}