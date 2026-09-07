package com.watchmen.tracker

import android.util.Log

class WarningCooldownManager {
    private val warningTimestamps = mutableMapOf<String, Long>()
    private val defaultCooldownMs = 5 * 60 * 1000L  // 5 minutes

    companion object {
        // Different cooldown periods for different warning types
        const val COOLDOWN_SIM_REMOVED = 5 * 60 * 1000L        // 5 minutes
        const val COOLDOWN_GPS_SPOOFING = 1 * 60 * 1000L       // 5 minutes
        const val COOLDOWN_FAKE_MOVEMENT : Long  = 250      // 5 minutes
        const val COOLDOWN_GEOFENCE = 3 * 60 * 1000L           // 3 minutes
        const val COOLDOWN_DATA_DELAY = 1 * 60 * 1000L        // 10 minutes
    }

    /**
     * Check if warning can be announced (not in cooldown)
     * @param warningType Unique identifier for the warning
     * @param cooldownMs Custom cooldown period (optional)
     * @return true if warning should be announced, false if still in cooldown
     */
    fun canAnnounce(warningType: String, cooldownMs: Long = defaultCooldownMs): Boolean {
        val now = System.currentTimeMillis()
        val lastWarning = warningTimestamps[warningType] ?: 0L
        val timeSinceLastWarning = now - lastWarning

        return if (timeSinceLastWarning >= cooldownMs) {
            warningTimestamps[warningType] = now
            Log.i("WarningCooldown", "✅ Announcing: $warningType (last: ${timeSinceLastWarning / 1000}s ago)")
            true
        } else {
            val remainingCooldown = (cooldownMs - timeSinceLastWarning) / 1000
            Log.d("WarningCooldown", "⏳ Suppressed: $warningType (cooldown: ${remainingCooldown}s remaining)")
            false
        }
    }

    /**
     * Force reset cooldown for a specific warning type
     */
    fun resetCooldown(warningType: String) {
        warningTimestamps.remove(warningType)
        Log.i("WarningCooldown", "🔄 Reset cooldown for: $warningType")
    }

    /**
     * Reset all cooldowns
     */
    fun resetAll() {
        warningTimestamps.clear()
        Log.i("WarningCooldown", "🔄 All cooldowns reset")
    }

    /**
     * Get remaining cooldown time in seconds
     */
    fun getRemainingCooldown(warningType: String, cooldownMs: Long = defaultCooldownMs): Long {
        val now = System.currentTimeMillis()
        val lastWarning = warningTimestamps[warningType] ?: return 0L
        val timeSinceLastWarning = now - lastWarning
        val remaining = cooldownMs - timeSinceLastWarning
        return if (remaining > 0) remaining / 1000 else 0L
    }
}
