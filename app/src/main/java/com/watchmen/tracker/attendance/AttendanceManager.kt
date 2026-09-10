package com.watchmen.tracker.attendance

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

object AttendanceManager {

    private const val TAG = "AttendanceManager"
    private const val PREF_NAME = "watchmen_attendance_prefs"

    private const val KEY_PENDING_CLOCK_IN_REQ_ID = "pending_clock_in_request_id"
    private const val KEY_PENDING_CLOCK_OUT_REQ_ID = "pending_clock_out_request_id"

    private var prefs: SharedPreferences? = null

    @Synchronized
    fun init(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun getPrefs(context: Context? = null): SharedPreferences {
        if (prefs == null && context != null) {
            init(context)
        }
        return prefs ?: throw IllegalStateException("AttendanceManager must be initialized with Context.")
    }

    // -------------------------------------------------------------
    // Idempotency: Request UUID Retention & Reuse
    // -------------------------------------------------------------

    /**
     * Retrieves existing pending Clock In request ID if previous attempt was interrupted/retried,
     * or generates and persists a new UUID.
     */
    @Synchronized
    fun getOrCreatePendingClockInRequestId(context: Context): String {
        val p = getPrefs(context)
        val existing = p.getString(KEY_PENDING_CLOCK_IN_REQ_ID, null)
        if (!existing.isNullOrBlank()) {
            Log.i(TAG, "Reusing pending Clock In request ID for retry: $existing")
            return existing
        }
        val newId = UUID.randomUUID().toString()
        p.edit().putString(KEY_PENDING_CLOCK_IN_REQ_ID, newId).apply()
        Log.i(TAG, "Generated new pending Clock In request ID: $newId")
        return newId
    }

    /**
     * Clears pending Clock In request ID upon verified backend confirmation.
     */
    @Synchronized
    fun clearPendingClockInRequestId(context: Context) {
        getPrefs(context).edit().remove(KEY_PENDING_CLOCK_IN_REQ_ID).apply()
        Log.i(TAG, "Cleared pending Clock In request ID.")
    }

    /**
     * Retrieves existing pending Clock Out request ID if previous attempt was interrupted/retried,
     * or generates and persists a new UUID.
     */
    @Synchronized
    fun getOrCreatePendingClockOutRequestId(context: Context): String {
        val p = getPrefs(context)
        val existing = p.getString(KEY_PENDING_CLOCK_OUT_REQ_ID, null)
        if (!existing.isNullOrBlank()) {
            Log.i(TAG, "Reusing pending Clock Out request ID for retry: $existing")
            return existing
        }
        val newId = UUID.randomUUID().toString()
        p.edit().putString(KEY_PENDING_CLOCK_OUT_REQ_ID, newId).apply()
        Log.i(TAG, "Generated new pending Clock Out request ID: $newId")
        return newId
    }

    /**
     * Clears pending Clock Out request ID upon verified backend confirmation.
     */
    @Synchronized
    fun clearPendingClockOutRequestId(context: Context) {
        getPrefs(context).edit().remove(KEY_PENDING_CLOCK_OUT_REQ_ID).apply()
        Log.i(TAG, "Cleared pending Clock Out request ID.")
    }

    // -------------------------------------------------------------
    // Monotonic Server Time Synchronization & Duration Calculation
    // -------------------------------------------------------------

    /**
     * Calculates the offset between the backend server's authoritative UTC clock and
     * the device's monotonic clock (SystemClock.elapsedRealtime()).
     *
     * serverOffsetMs = serverEpochMs - elapsedRealtimeMs
     */
    fun calculateServerOffsetMs(serverTimeIso: String, elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()): Long {
        val serverEpochMs = parseIsoToEpochMs(serverTimeIso) ?: System.currentTimeMillis()
        return serverEpochMs - elapsedRealtimeMs
    }

    /**
     * Computes the current duty elapsed duration in milliseconds from the authoritative
     * server clock_in_at timestamp using the monotonic server offset.
     */
    fun calculateElapsedDutyMs(
        clockInAtIso: String,
        serverOffsetMs: Long,
        elapsedRealtimeMs: Long = SystemClock.elapsedRealtime()
    ): Long {
        val clockInEpochMs = parseIsoToEpochMs(clockInAtIso) ?: return 0L
        val currentServerEpochMs = elapsedRealtimeMs + serverOffsetMs
        return kotlin.math.max(0L, currentServerEpochMs - clockInEpochMs)
    }

    /**
     * Formats milliseconds into standard live timer format: "HH:mm:ss" or "mm:ss"
     */
    fun formatTimerHms(durationMs: Long): String {
        val totalSeconds = kotlin.math.max(0L, durationMs / 1000L)
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L

        return if (hours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Formats total worked minutes into readable text: e.g. "11h 55m" or "42m"
     */
    fun formatWorkedDuration(totalMinutes: Int?): String {
        if (totalMinutes == null || totalMinutes < 0) return "--"
        val hours = totalMinutes / 60
        val mins = totalMinutes % 60
        return if (hours > 0) {
            "${hours}h ${mins}m"
        } else {
            "${mins}m"
        }
    }

    /**
     * Formats ISO 8601 UTC timestamp into local 12-hour display time: e.g. "07:03 AM"
     */
    fun formatTimestamp12h(isoTimestamp: String?, timezoneId: String = "Asia/Dubai"): String {
        if (isoTimestamp.isNullOrBlank()) return "--:--"
        return try {
            val instant = Instant.parse(isoTimestamp)
            val zone = try {
                ZoneId.of(timezoneId)
            } catch (e: Exception) {
                ZoneId.of("Asia/Dubai")
            }
            val zdt = instant.atZone(zone)
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
            zdt.format(formatter)
        } catch (e: Exception) {
            try {
                // Fallback attempt: parsing ZonedDateTime directly
                val zdt = ZonedDateTime.parse(isoTimestamp)
                val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
                zdt.format(formatter)
            } catch (e2: Exception) {
                "--:--"
            }
        }
    }

    /**
     * Formats wall-clock time string (e.g. "07:00:00" or "19:00:00") into 12-hour: "07:00 AM"
     */
    fun formatWallClock12h(timeStr: String?): String {
        if (timeStr.isNullOrBlank()) return "--:--"
        return try {
            val localTime = LocalTime.parse(timeStr.trim())
            val formatter = DateTimeFormatter.ofPattern("hh:mm a", Locale.US)
            localTime.format(formatter)
        } catch (e: Exception) {
            timeStr
        }
    }

    /**
     * Parses ISO 8601 timestamp string to Unix epoch millisecond.
     */
    fun parseIsoToEpochMs(isoStr: String?): Long? {
        if (isoStr.isNullOrBlank()) return null
        return try {
            Instant.parse(isoStr.trim()).toEpochMilli()
        } catch (e: Exception) {
            try {
                ZonedDateTime.parse(isoStr.trim()).toInstant().toEpochMilli()
            } catch (e2: Exception) {
                null
            }
        }
    }
}
