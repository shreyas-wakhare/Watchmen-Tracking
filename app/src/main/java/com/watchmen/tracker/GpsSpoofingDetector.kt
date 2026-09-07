package com.watchmen.tracker

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log

class GpsSpoofingDetector {

    companion object {
        private const val TAG = "GPSSpoofDetector"
    }

    data class SpoofingResult(
        val isSpoofed: Boolean,
        val confidence: Float,
        val reasons: List<String>
    )

    fun detectSpoofing(
        location: Location,
        previousLocation: Location?,
        context: Context
    ): SpoofingResult {
        val reasons = mutableListOf<String>()
        var suspicionScore = 0f

        // ✅ 1. Check if location is mocked (Android flag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (location.isMock) {
                reasons.add("Android Mock Location Flag Detected")
                suspicionScore += 0.4f
            }
        } else {
            if (location.isFromMockProvider) {
                reasons.add("Mock Provider Detected")
                suspicionScore += 0.4f
            }
        }

        // ✅ 2. Check if speed is physically impossible
        if (location.hasSpeed() && location.speed > 100f) { // >360 km/h
            reasons.add("Impossible speed: ${location.speed} m/s")
            suspicionScore += 0.3f
        }

        // ✅ 3. Check for instant teleportation
        previousLocation?.let { prev ->
            val distance = FloatArray(1)
            Location.distanceBetween(
                prev.latitude, prev.longitude,
                location.latitude, location.longitude,
                distance
            )

            val timeDelta = (location.time - prev.time) / 1000f // seconds
            if (timeDelta > 0 && timeDelta < 60) {
                val impliedSpeed = distance[0] / timeDelta
                if (impliedSpeed > 100f) { // >360 km/h
                    reasons.add("Teleportation detected: ${impliedSpeed} m/s")
                    suspicionScore += 0.4f
                }
            }
        }

        // ✅ 4. Check accuracy (fake GPS often has perfect accuracy)
        if (location.hasAccuracy() && location.accuracy < 1f) {
            reasons.add("Suspiciously perfect accuracy: ${location.accuracy}m")
            suspicionScore += 0.2f
        }

        // ✅ 5. Check altitude (fake GPS often at sea level)
        if (location.hasAltitude() && location.altitude == 0.0) {
            reasons.add("Altitude exactly 0m (suspicious)")
            suspicionScore += 0.1f
        }

        // ✅ 6. Check bearing consistency
        if (location.hasBearing() && previousLocation?.hasBearing() == true) {
            val bearingDiff = Math.abs(location.bearing - previousLocation.bearing)
            if (bearingDiff > 180f) {
                reasons.add("Instant bearing reversal: ${bearingDiff}°")
                suspicionScore += 0.2f
            }
        }

        // ✅ 7. Check for enabled mock location apps
        val mockLocationAppsEnabled = isMockLocationEnabled(context)
        if (mockLocationAppsEnabled) {
            reasons.add("Mock Location Setting Enabled")
            suspicionScore += 0.3f
        }

        val confidence = suspicionScore.coerceIn(0f, 1f)
        val isSpoofed = confidence > 0.5f

        if (isSpoofed) {
            Log.w(TAG, "⚠️ GPS SPOOFING DETECTED! Confidence: ${(confidence * 100).toInt()}%")
            reasons.forEach { Log.w(TAG, "  - $it") }
        }

        return SpoofingResult(isSpoofed, confidence, reasons)
    }

    private fun isMockLocationEnabled(context: Context): Boolean {
        return try {
            val opsManager = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
            val result = opsManager.checkOpNoThrow(
                android.app.AppOpsManager.OPSTR_MOCK_LOCATION,
                android.os.Process.myUid(),
                context.packageName
            )
            result == android.app.AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
