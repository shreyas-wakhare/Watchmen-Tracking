package com.watchmen.tracker.attendance

import org.json.JSONArray
import org.json.JSONObject

/**
 * Data models for Watchman Attendance / Check-In / Check-Out system.
 * Follows the frozen contracts defined in database.md and backend.md.
 */

data class ScheduleRequest(
    val shiftName: String,
    val startTime: String, // "HH:mm:ss"
    val endTime: String,   // "HH:mm:ss"
    val timezone: String,  // e.g. "Asia/Dubai"
    val daysOfWeek: String // e.g. "1,2,3,4,5,6,7"
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("shift_name", shiftName.trim())
        json.put("start_time", startTime.trim())
        json.put("end_time", endTime.trim())
        json.put("timezone", timezone.trim())
        json.put("days_of_week", daysOfWeek.trim())
        return json.toString()
    }
}

data class ScheduleResponse(
    val id: Int,
    val userId: Int,
    val shiftName: String,
    val startTime: String,
    val endTime: String,
    val timezone: String,
    val daysOfWeek: String,
    val isActive: Boolean,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun fromJson(json: JSONObject): ScheduleResponse {
            return ScheduleResponse(
                id = json.optInt("id", -1),
                userId = json.optInt("user_id", -1),
                shiftName = json.optString("shift_name", "Standard Shift"),
                startTime = json.optString("start_time", "07:00:00"),
                endTime = json.optString("end_time", "19:00:00"),
                timezone = json.optString("timezone", "Asia/Dubai"),
                daysOfWeek = json.optString("days_of_week", "1,2,3,4,5,6,7"),
                isActive = json.optBoolean("is_active", true),
                createdAt = json.optString("created_at", ""),
                updatedAt = json.optString("updated_at", "")
            )
        }
    }
}

data class ClockInRequest(
    val requestId: String,
    val deviceId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    init {
        // Enforce coordinate pair rule: both present or both null
        val hasLat = latitude != null
        val hasLon = longitude != null
        require(hasLat == hasLon) { "Both latitude and longitude must be provided together or omitted" }
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("request_id", requestId.trim())
        if (!deviceId.isNullOrBlank()) {
            json.put("device_id", deviceId.trim())
        }
        if (latitude != null && longitude != null) {
            json.put("latitude", latitude)
            json.put("longitude", longitude)
        }
        return json.toString()
    }
}

data class ClockOutRequest(
    val requestId: String,
    val deviceId: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    init {
        // Enforce coordinate pair rule: both present or both null
        val hasLat = latitude != null
        val hasLon = longitude != null
        require(hasLat == hasLon) { "Both latitude and longitude must be provided together or omitted" }
    }

    fun toJson(): String {
        val json = JSONObject()
        json.put("request_id", requestId.trim())
        if (!deviceId.isNullOrBlank()) {
            json.put("device_id", deviceId.trim())
        }
        if (latitude != null && longitude != null) {
            json.put("latitude", latitude)
            json.put("longitude", longitude)
        }
        return json.toString()
    }
}

data class AttendanceRecordResponse(
    val id: Int,
    val userId: Int,
    val workDate: String,
    val scheduledStartTime: String,
    val scheduledEndTime: String,
    val timezone: String,
    val clockInAt: String,
    val clockOutAt: String?,
    val clockInRequestId: String,
    val clockOutRequestId: String?,
    val clockInDeviceId: String?,
    val clockOutDeviceId: String?,
    val clockInLat: Double?,
    val clockInLon: Double?,
    val clockOutLat: Double?,
    val clockOutLon: Double?,
    val totalWorkedMinutes: Int?,
    val status: String,
    val notes: String?,
    val createdAt: String,
    val updatedAt: String
) {
    companion object {
        fun fromJson(json: JSONObject): AttendanceRecordResponse {
            val clockOutAtVal = if (json.isNull("clock_out_at")) null else json.optString("clock_out_at").takeIf { it.isNotBlank() }
            val clockOutReqIdVal = if (json.isNull("clock_out_request_id")) null else json.optString("clock_out_request_id").takeIf { it.isNotBlank() }
            val clockInDevIdVal = if (json.isNull("clock_in_device_id")) null else json.optString("clock_in_device_id").takeIf { it.isNotBlank() }
            val clockOutDevIdVal = if (json.isNull("clock_out_device_id")) null else json.optString("clock_out_device_id").takeIf { it.isNotBlank() }
            val clockInLatVal = if (json.isNull("clock_in_lat")) null else json.optDouble("clock_in_lat").takeIf { !it.isNaN() }
            val clockInLonVal = if (json.isNull("clock_in_lon")) null else json.optDouble("clock_in_lon").takeIf { !it.isNaN() }
            val clockOutLatVal = if (json.isNull("clock_out_lat")) null else json.optDouble("clock_out_lat").takeIf { !it.isNaN() }
            val clockOutLonVal = if (json.isNull("clock_out_lon")) null else json.optDouble("clock_out_lon").takeIf { !it.isNaN() }
            val totalMinsVal = if (json.isNull("total_worked_minutes")) null else json.optInt("total_worked_minutes")
            val notesVal = if (json.isNull("notes")) null else json.optString("notes").takeIf { it.isNotBlank() }

            return AttendanceRecordResponse(
                id = json.optInt("id", -1),
                userId = json.optInt("user_id", -1),
                workDate = json.optString("work_date", ""),
                scheduledStartTime = json.optString("scheduled_start_time", ""),
                scheduledEndTime = json.optString("scheduled_end_time", ""),
                timezone = json.optString("timezone", "Asia/Dubai"),
                clockInAt = json.optString("clock_in_at", ""),
                clockOutAt = clockOutAtVal,
                clockInRequestId = json.optString("clock_in_request_id", ""),
                clockOutRequestId = clockOutReqIdVal,
                clockInDeviceId = clockInDevIdVal,
                clockOutDeviceId = clockOutDevIdVal,
                clockInLat = clockInLatVal,
                clockInLon = clockInLonVal,
                clockOutLat = clockOutLatVal,
                clockOutLon = clockOutLonVal,
                totalWorkedMinutes = totalMinsVal,
                status = json.optString("status", "CLOCKED_IN"),
                notes = notesVal,
                createdAt = json.optString("created_at", ""),
                updatedAt = json.optString("updated_at", "")
            )
        }
    }
}

data class TodayAttendanceResponse(
    val state: String, // NOT_STARTED, CLOCKED_IN, CLOCKED_OUT, PENDING_RESOLUTION
    val workDate: String,
    val schedule: ScheduleResponse?,
    val attendance: AttendanceRecordResponse?,
    val runningMinutes: Int?,
    val serverTime: String
) {
    companion object {
        fun fromJson(json: JSONObject): TodayAttendanceResponse {
            val schedJson = json.optJSONObject("schedule")
            val attJson = json.optJSONObject("attendance")
            val runningMins = if (json.isNull("running_minutes")) null else json.optInt("running_minutes")

            return TodayAttendanceResponse(
                state = json.optString("state", "NOT_STARTED"),
                workDate = json.optString("work_date", ""),
                schedule = schedJson?.let { ScheduleResponse.fromJson(it) },
                attendance = attJson?.let { AttendanceRecordResponse.fromJson(it) },
                runningMinutes = runningMins,
                serverTime = json.optString("server_time", "")
            )
        }
    }
}

data class ActiveAttendanceResponse(
    val hasActive: Boolean,
    val attendance: AttendanceRecordResponse?,
    val runningMinutes: Int?,
    val serverTime: String
) {
    companion object {
        fun fromJson(json: JSONObject): ActiveAttendanceResponse {
            val attJson = json.optJSONObject("attendance")
            val runningMins = if (json.isNull("running_minutes")) null else json.optInt("running_minutes")

            return ActiveAttendanceResponse(
                hasActive = json.optBoolean("has_active", false),
                attendance = attJson?.let { AttendanceRecordResponse.fromJson(it) },
                runningMinutes = runningMins,
                serverTime = json.optString("server_time", "")
            )
        }
    }
}

data class AttendanceHistoryResponse(
    val total: Int,
    val items: List<AttendanceRecordResponse>,
    val limit: Int,
    val offset: Int
) {
    companion object {
        fun fromJson(json: JSONObject): AttendanceHistoryResponse {
            val itemsArray = json.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<AttendanceRecordResponse>()
            for (i in 0 until itemsArray.length()) {
                val itemObj = itemsArray.optJSONObject(i)
                if (itemObj != null) {
                    list.add(AttendanceRecordResponse.fromJson(itemObj))
                }
            }

            return AttendanceHistoryResponse(
                total = json.optInt("total", 0),
                items = list,
                limit = json.optInt("limit", 20),
                offset = json.optInt("offset", 0)
            )
        }
    }
}

sealed class AttendanceResult<out T> {
    data class Success<out T>(val data: T) : AttendanceResult<T>()
    data class Error(
        val message: String,
        val statusCode: Int? = null,
        val isNetworkError: Boolean = false
    ) : AttendanceResult<Nothing>()
}
