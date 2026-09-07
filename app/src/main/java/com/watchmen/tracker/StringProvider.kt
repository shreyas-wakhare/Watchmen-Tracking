package com.watchmen.tracker

import android.content.Context
import android.util.Log
import androidx.annotation.StringRes
import androidx.annotation.VisibleForTesting
import java.util.concurrent.ConcurrentHashMap

/**
 * Interface for providing externalized strings.
 */
interface StringProvider {
    fun getString(key: String, vararg formatArgs: Any): String
}

/**
 * Concrete implementation using Android's string resources.
 */
class AndroidResourceStringProvider(private val context: Context) : StringProvider {

    // Map keys to their R.string.id - In production, this can be pre-generated or simplified.
    // We simulate this lookup based on the user's strings.xml for demonstration.
    private val keyToResourceMap = ConcurrentHashMap<String, Int>()

    init {
        // Dynamically get resource IDs. This is necessary because we cannot use R.string directly.
        val packageName = context.packageName
        listOf(
            "liveness_turn_head", "liveness_blink", "liveness_move_closer", "liveness_verified",
            "liveness_no_face", "liveness_eyes_closed", "liveness_tracking_weak", "liveness_hold_steady",
            "liveness_motion_required", "liveness_blink_too_long", "liveness_head_passive", "liveness_head_ok",
            "liveness_motion_ok", "liveness_building_motion", "liveness_eyes_open", "liveness_waiting_blink",
            "liveness_tracking_ok", "liveness_too_small", "liveness_analysis_error", "liveness_no_face_error",
            "liveness_low_light", "liveness_trial_prompt", "liveness_final_try_prompt", "liveness_failed_trial_report",
            "button_capture_anyway", "hint_force_capture", "button_verifying", "button_capture",
            "button_override", "hint_verification_failed", "force_capture_enabled"
        ).forEach { key ->
            val resId = context.resources.getIdentifier(key, "string", packageName)
            if (resId != 0) {
                keyToResourceMap[key] = resId
            } else {
                Log.e("StringProvider", "Missing string resource ID for key: $key")
            }
        }
    }

    override fun getString(key: String, vararg formatArgs: Any): String {
        val resId = keyToResourceMap[key] ?: return "MISSING_STRING:$key"

        return try {
            context.getString(resId, *formatArgs)
        } catch (e: Exception) {
            Log.e("StringProvider", "Error getting string for key $key (ID $resId): ${e.message}")
            // Fallback to key or simplified format
            "FORMAT_ERROR:$key"
        }
    }
}