package com.watchmen.tracker.attendance

import org.junit.Assert.*
import org.junit.Test

class AttendanceManagerTest {

    @Test
    fun testServerOffsetCalculation() {
        val serverTimeIso = "2026-09-10T10:00:00Z"
        val serverEpochMs = AttendanceManager.parseIsoToEpochMs(serverTimeIso)!!
        val elapsedRealtimeMs = 50000L

        val offsetMs = AttendanceManager.calculateServerOffsetMs(serverTimeIso, elapsedRealtimeMs)
        assertEquals(serverEpochMs - elapsedRealtimeMs, offsetMs)
    }

    @Test
    fun testElapsedDutyCalculationZero() {
        val clockInIso = "2026-09-10T07:00:00Z"
        val clockInEpoch = AttendanceManager.parseIsoToEpochMs(clockInIso)!!
        val elapsedRealtime = 100000L
        val offset = clockInEpoch - elapsedRealtime

        val dutyMs = AttendanceManager.calculateElapsedDutyMs(clockInIso, offset, elapsedRealtime)
        assertEquals(0L, dutyMs)
    }

    @Test
    fun testElapsedDutyCalculationNormal() {
        val clockInIso = "2026-09-10T07:00:00Z"
        val clockInEpoch = AttendanceManager.parseIsoToEpochMs(clockInIso)!!
        val elapsedRealtime = 100000L
        val offset = clockInEpoch - elapsedRealtime

        // Advance monotonic clock by 3600 seconds (1 hour)
        val nowElapsedRealtime = elapsedRealtime + 3600000L
        val dutyMs = AttendanceManager.calculateElapsedDutyMs(clockInIso, offset, nowElapsedRealtime)
        assertEquals(3600000L, dutyMs)
    }

    @Test
    fun testElapsedDutyNegativeSafety() {
        val clockInIso = "2026-09-10T07:00:00Z"
        val clockInEpoch = AttendanceManager.parseIsoToEpochMs(clockInIso)!!
        val elapsedRealtime = 100000L
        val offset = clockInEpoch - elapsedRealtime

        // Simulate edge condition where now is somehow before clock_in
        val earlierElapsedRealtime = elapsedRealtime - 5000L
        val dutyMs = AttendanceManager.calculateElapsedDutyMs(clockInIso, offset, earlierElapsedRealtime)
        assertEquals(0L, dutyMs)
    }

    @Test
    fun testFormatTimerHmsZero() {
        assertEquals("00:00", AttendanceManager.formatTimerHms(0L))
    }

    @Test
    fun testFormatTimerHmsMinutes() {
        val ms = (42 * 60 + 15) * 1000L // 42m 15s
        assertEquals("42:15", AttendanceManager.formatTimerHms(ms))
    }

    @Test
    fun testFormatTimerHmsHours() {
        val ms = (3 * 3600 + 42 * 60 + 15) * 1000L // 03:42:15
        assertEquals("03:42:15", AttendanceManager.formatTimerHms(ms))
    }

    @Test
    fun testFormatWorkedDuration() {
        assertEquals("11h 55m", AttendanceManager.formatWorkedDuration(715))
        assertEquals("45m", AttendanceManager.formatWorkedDuration(45))
        assertEquals("0m", AttendanceManager.formatWorkedDuration(0))
        assertEquals("--", AttendanceManager.formatWorkedDuration(null))
        assertEquals("--", AttendanceManager.formatWorkedDuration(-5))
    }

    @Test
    fun testFormatTimestamp12hWithTimezone() {
        // 03:00:00 UTC = 07:00:00 Asia/Dubai (UTC+4)
        val isoUtc = "2026-09-10T03:00:00Z"
        val formatted = AttendanceManager.formatTimestamp12h(isoUtc, "Asia/Dubai")
        assertEquals("07:00 AM", formatted)

        // 15:00:00 UTC = 19:00:00 Asia/Dubai (UTC+4)
        val isoOutUtc = "2026-09-10T15:00:00Z"
        val formattedOut = AttendanceManager.formatTimestamp12h(isoOutUtc, "Asia/Dubai")
        assertEquals("07:00 PM", formattedOut)
    }

    @Test
    fun testFormatWallClock12h() {
        assertEquals("07:00 AM", AttendanceManager.formatWallClock12h("07:00:00"))
        assertEquals("07:00 PM", AttendanceManager.formatWallClock12h("19:00:00"))
        assertEquals("12:30 PM", AttendanceManager.formatWallClock12h("12:30:00"))
        assertEquals("12:05 AM", AttendanceManager.formatWallClock12h("00:05:00"))
        assertEquals("--:--", AttendanceManager.formatWallClock12h(null))
    }

    @Test
    fun testParseIsoToEpochMs() {
        val msUtc = AttendanceManager.parseIsoToEpochMs("2026-09-10T00:00:00Z")
        assertNotNull(msUtc)

        val msWithOffset = AttendanceManager.parseIsoToEpochMs("2026-09-10T04:00:00+04:00")
        assertNotNull(msWithOffset)
        assertEquals(msUtc, msWithOffset)

        assertNull(AttendanceManager.parseIsoToEpochMs(null))
        assertNull(AttendanceManager.parseIsoToEpochMs(""))
        assertNull(AttendanceManager.parseIsoToEpochMs("invalid-timestamp"))
    }
}
