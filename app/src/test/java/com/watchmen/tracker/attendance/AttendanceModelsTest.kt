package com.watchmen.tracker.attendance

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AttendanceModelsTest {

    @Test
    fun testScheduleRequestJsonSerialization() {
        val req = ScheduleRequest(
            shiftName = "Day Shift",
            startTime = "07:00:00",
            endTime = "19:00:00",
            timezone = "Asia/Dubai",
            daysOfWeek = "1,2,3,4,5,6,7"
        )
        val json = JSONObject(req.toJson())
        assertEquals("Day Shift", json.getString("shift_name"))
        assertEquals("07:00:00", json.getString("start_time"))
        assertEquals("19:00:00", json.getString("end_time"))
        assertEquals("Asia/Dubai", json.getString("timezone"))
        assertEquals("1,2,3,4,5,6,7", json.getString("days_of_week"))
    }

    @Test
    fun testScheduleResponseDeserialization() {
        val json = JSONObject().apply {
            put("id", 10)
            put("user_id", 42)
            put("shift_name", "Night Duty")
            put("start_time", "19:00:00")
            put("end_time", "07:00:00")
            put("timezone", "Asia/Dubai")
            put("days_of_week", "1,2,3,4,5")
            put("is_active", true)
            put("created_at", "2026-09-10T07:00:00Z")
            put("updated_at", "2026-09-10T07:00:00Z")
        }
        val res = ScheduleResponse.fromJson(json)
        assertEquals(10, res.id)
        assertEquals(42, res.userId)
        assertEquals("Night Duty", res.shiftName)
        assertEquals("19:00:00", res.startTime)
        assertEquals("07:00:00", res.endTime)
        assertEquals("Asia/Dubai", res.timezone)
        assertEquals("1,2,3,4,5", res.daysOfWeek)
        assertTrue(res.isActive)
    }

    @Test
    fun testClockInRequestWithCoordinates() {
        val req = ClockInRequest(
            requestId = "req-uuid-1234",
            deviceId = "Device_Alpha",
            latitude = 25.2048,
            longitude = 55.2708
        )
        val json = JSONObject(req.toJson())
        assertEquals("req-uuid-1234", json.getString("request_id"))
        assertEquals("Device_Alpha", json.getString("device_id"))
        assertEquals(25.2048, json.getDouble("latitude"), 0.0001)
        assertEquals(55.2708, json.getDouble("longitude"), 0.0001)
    }

    @Test
    fun testClockInRequestWithoutCoordinates() {
        val req = ClockInRequest(
            requestId = "req-uuid-5678",
            deviceId = "Device_Beta"
        )
        val json = JSONObject(req.toJson())
        assertEquals("req-uuid-5678", json.getString("request_id"))
        assertEquals("Device_Beta", json.getString("device_id"))
        assertFalse(json.has("latitude"))
        assertFalse(json.has("longitude"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun testClockInRequestRejectsLatitudeWithoutLongitude() {
        ClockInRequest(
            requestId = "req-invalid",
            latitude = 25.2048,
            longitude = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun testClockInRequestRejectsLongitudeWithoutLatitude() {
        ClockInRequest(
            requestId = "req-invalid",
            latitude = null,
            longitude = 55.2708
        )
    }

    @Test
    fun testClockOutRequestSerialization() {
        val req = ClockOutRequest(
            requestId = "out-uuid-9999",
            deviceId = "Device_Omega",
            latitude = 25.2050,
            longitude = 55.2710
        )
        val json = JSONObject(req.toJson())
        assertEquals("out-uuid-9999", json.getString("request_id"))
        assertEquals("Device_Omega", json.getString("device_id"))
        assertEquals(25.2050, json.getDouble("latitude"), 0.0001)
        assertEquals(55.2710, json.getDouble("longitude"), 0.0001)
    }

    @Test
    fun testTodayAttendanceResponseNotStarted() {
        val json = JSONObject().apply {
            put("state", "NOT_STARTED")
            put("work_date", "2026-09-10")
            put("schedule", JSONObject().apply {
                put("id", 1)
                put("user_id", 10)
                put("shift_name", "Day Shift")
                put("start_time", "07:00:00")
                put("end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
                put("days_of_week", "1,2,3,4,5,6,7")
                put("is_active", true)
            })
            put("attendance", JSONObject.NULL)
            put("running_minutes", JSONObject.NULL)
            put("server_time", "2026-09-10T05:00:00Z")
        }

        val today = TodayAttendanceResponse.fromJson(json)
        assertEquals("NOT_STARTED", today.state)
        assertEquals("2026-09-10", today.workDate)
        assertNotNull(today.schedule)
        assertEquals("Day Shift", today.schedule?.shiftName)
        assertNull(today.attendance)
        assertNull(today.runningMinutes)
    }

    @Test
    fun testTodayAttendanceResponseClockedIn() {
        val json = JSONObject().apply {
            put("state", "CLOCKED_IN")
            put("work_date", "2026-09-10")
            put("schedule", JSONObject().apply {
                put("id", 1)
                put("shift_name", "Day Shift")
                put("start_time", "07:00:00")
                put("end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
            })
            put("attendance", JSONObject().apply {
                put("id", 501)
                put("user_id", 10)
                put("work_date", "2026-09-10")
                put("scheduled_start_time", "07:00:00")
                put("scheduled_end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
                put("clock_in_at", "2026-09-10T03:00:00Z")
                put("clock_out_at", JSONObject.NULL)
                put("clock_in_request_id", "req-in-501")
                put("status", "CLOCKED_IN")
            })
            put("running_minutes", 120)
            put("server_time", "2026-09-10T05:00:00Z")
        }

        val today = TodayAttendanceResponse.fromJson(json)
        assertEquals("CLOCKED_IN", today.state)
        assertEquals(120, today.runningMinutes)
        assertNotNull(today.attendance)
        assertEquals(501, today.attendance?.id)
        assertEquals("2026-09-10T03:00:00Z", today.attendance?.clockInAt)
        assertNull(today.attendance?.clockOutAt)
    }

    @Test
    fun testTodayAttendanceResponseClockedOut() {
        val json = JSONObject().apply {
            put("state", "CLOCKED_OUT")
            put("work_date", "2026-09-10")
            put("schedule", JSONObject().apply {
                put("id", 1)
                put("shift_name", "Day Shift")
                put("start_time", "07:00:00")
                put("end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
            })
            put("attendance", JSONObject().apply {
                put("id", 501)
                put("user_id", 10)
                put("work_date", "2026-09-10")
                put("scheduled_start_time", "07:00:00")
                put("scheduled_end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
                put("clock_in_at", "2026-09-10T03:00:00Z")
                put("clock_out_at", "2026-09-10T15:00:00Z")
                put("clock_in_request_id", "req-in-501")
                put("clock_out_request_id", "req-out-501")
                put("total_worked_minutes", 720)
                put("status", "CLOCKED_OUT")
            })
            put("running_minutes", JSONObject.NULL)
            put("server_time", "2026-09-10T15:05:00Z")
        }

        val today = TodayAttendanceResponse.fromJson(json)
        assertEquals("CLOCKED_OUT", today.state)
        assertNotNull(today.attendance)
        assertEquals("2026-09-10T15:00:00Z", today.attendance?.clockOutAt)
        assertEquals(720, today.attendance?.totalWorkedMinutes)
    }

    @Test
    fun testTodayAttendanceResponsePendingResolution() {
        val json = JSONObject().apply {
            put("state", "PENDING_RESOLUTION")
            put("work_date", "2026-09-10")
            put("attendance", JSONObject().apply {
                put("id", 480)
                put("user_id", 10)
                put("work_date", "2026-09-09")
                put("scheduled_start_time", "07:00:00")
                put("scheduled_end_time", "19:00:00")
                put("timezone", "Asia/Dubai")
                put("clock_in_at", "2026-09-09T03:00:00Z")
                put("clock_out_at", JSONObject.NULL)
                put("status", "PENDING_RESOLUTION")
            })
            put("running_minutes", JSONObject.NULL)
            put("server_time", "2026-09-10T08:00:00Z")
        }

        val today = TodayAttendanceResponse.fromJson(json)
        assertEquals("PENDING_RESOLUTION", today.state)
        assertEquals("2026-09-09", today.attendance?.workDate)
        assertNull(today.attendance?.clockOutAt)
    }

    @Test
    fun testAttendanceHistoryResponseParsing() {
        val item1 = JSONObject().apply {
            put("id", 201)
            put("user_id", 10)
            put("work_date", "2026-09-10")
            put("scheduled_start_time", "07:00:00")
            put("scheduled_end_time", "19:00:00")
            put("timezone", "Asia/Dubai")
            put("clock_in_at", "2026-09-10T03:00:00Z")
            put("clock_out_at", "2026-09-10T15:00:00Z")
            put("total_worked_minutes", 720)
            put("status", "CLOCKED_OUT")
        }
        val json = JSONObject().apply {
            put("total", 1)
            put("items", JSONArray().apply { put(item1) })
            put("limit", 20)
            put("offset", 0)
        }

        val history = AttendanceHistoryResponse.fromJson(json)
        assertEquals(1, history.total)
        assertEquals(1, history.items.size)
        assertEquals(201, history.items[0].id)
        assertEquals(720, history.items[0].totalWorkedMinutes)
    }
}
