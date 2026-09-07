package com.watchmen.tracker

import android.content.Context
import android.location.Location
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class OfflineGpsStorage(private val context: Context) {

    private val storageDir = File(context.filesDir, "offline_tracks").apply { mkdirs() }

    data class TrackPoint(
        val latitude: Double,
        val longitude: Double,
        val timestamp: Long,
        val accuracy: Float,
        val speed: Float,
        val bearing: Float,
        val state: String
    )

    data class TrackSession(
        val sessionId: String,
        val startTime: Long,
        val endTime: Long,
        val points: List<TrackPoint>,
        val totalDistance: Float,
        val duration: Long
    )

    /**
     * Save a location point to offline storage
     */
    fun saveLocationPoint(
        location: Location,
        state: String,
        sessionId: String = getCurrentSessionId()
    ) {
        try {
            val point = TrackPoint(
                latitude = location.latitude,
                longitude = location.longitude,
                timestamp = System.currentTimeMillis(),
                accuracy = location.accuracy,
                speed = if (location.hasSpeed()) location.speed else 0f,
                bearing = if (location.hasBearing()) location.bearing else 0f,
                state = state
            )

            val file = getSessionFile(sessionId)
            val json = pointToJson(point)

            // Append to file
            file.appendText(json.toString() + "\n")

        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to save point: ${e.message}")
        }
    }

    /**
     * Get all track sessions
     */
    fun getAllSessions(): List<TrackSession> {
        return try {
            storageDir.listFiles()
                ?.filter { it.extension == "track" }
                ?.mapNotNull { loadSession(it) }
                ?.sortedByDescending { it.startTime }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to load sessions: ${e.message}")
            emptyList()
        }
    }

    /**
     * Get session by ID
     */
    fun getSession(sessionId: String): TrackSession? {
        return try {
            val file = getSessionFile(sessionId)
            if (file.exists()) {
                loadSession(file)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to load session: ${e.message}")
            null
        }
    }

    /**
     * Export session as GeoJSON for offline map viewing
     */
    fun exportSessionAsGeoJson(sessionId: String): String? {
        return try {
            val session = getSession(sessionId) ?: return null

            val geoJson = JSONObject().apply {
                put("type", "FeatureCollection")
                put("features", JSONArray().apply {
                    // Add line feature
                    put(JSONObject().apply {
                        put("type", "Feature")
                        put("geometry", JSONObject().apply {
                            put("type", "LineString")
                            put("coordinates", JSONArray().apply {
                                session.points.forEach { point ->
                                    put(JSONArray().apply {
                                        put(point.longitude)
                                        put(point.latitude)
                                    })
                                }
                            })
                        })
                        put("properties", JSONObject().apply {
                            put("session_id", sessionId)
                            put("start_time", session.startTime)
                            put("end_time", session.endTime)
                            put("total_distance", session.totalDistance)
                            put("duration", session.duration)
                        })
                    })

                    // Add point features
                    session.points.forEach { point ->
                        put(JSONObject().apply {
                            put("type", "Feature")
                            put("geometry", JSONObject().apply {
                                put("type", "Point")
                                put("coordinates", JSONArray().apply {
                                    put(point.longitude)
                                    put(point.latitude)
                                })
                            })
                            put("properties", JSONObject().apply {
                                put("timestamp", point.timestamp)
                                put("speed", point.speed)
                                put("state", point.state)
                                put("accuracy", point.accuracy)
                            })
                        })
                    }
                })
            }

            geoJson.toString(2)
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to export GeoJSON: ${e.message}")
            null
        }
    }

    /**
     * Delete old sessions (cleanup)
     */
    fun deleteOldSessions(daysToKeep: Int = 30) {
        try {
            val cutoffTime = System.currentTimeMillis() - (daysToKeep * 24 * 60 * 60 * 1000L)

            storageDir.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoffTime) {
                    file.delete()
                    Log.i("OfflineGPS", "Deleted old session: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to delete old sessions: ${e.message}")
        }
    }

    /**
     * Delete a specific synced session file
     */
    fun deleteSession(sessionId: String): Boolean {
        return try {
            val file = getSessionFile(sessionId)
            if (file.exists()) {
                val deleted = file.delete()
                Log.i("OfflineGPS", "Deleted synced session file ${file.name}: $deleted")
                deleted
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to delete session $sessionId: ${e.message}")
            false
        }
    }

    /**
     * Clear all offline session files
     */
    fun clearAllSessions() {
        try {
            storageDir.listFiles()?.filter { it.extension == "track" }?.forEach { it.delete() }
            Log.i("OfflineGPS", "Cleared all offline sessions")
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to clear offline sessions: ${e.message}")
        }
    }


    // ===== PRIVATE HELPERS =====

    private fun getCurrentSessionId(): String {
        val prefs = context.getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        var sessionId = prefs.getString("current_session_id", null)

        if (sessionId == null) {
            sessionId = "session_${System.currentTimeMillis()}"
            prefs.edit().putString("current_session_id", sessionId).apply()
        }

        return sessionId
    }

    private fun getSessionFile(sessionId: String): File {
        return File(storageDir, "$sessionId.track")
    }

    private fun pointToJson(point: TrackPoint): JSONObject {
        return JSONObject().apply {
            put("lat", point.latitude)
            put("lon", point.longitude)
            put("ts", point.timestamp)
            put("acc", point.accuracy)
            put("spd", point.speed)
            put("brg", point.bearing)
            put("st", point.state)
        }
    }

    private fun jsonToPoint(json: JSONObject): TrackPoint {
        return TrackPoint(
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lon"),
            timestamp = json.getLong("ts"),
            accuracy = json.getDouble("acc").toFloat(),
            speed = json.getDouble("spd").toFloat(),
            bearing = json.getDouble("brg").toFloat(),
            state = json.getString("st")
        )
    }

    private fun loadSession(file: File): TrackSession? {
        return try {
            val points = mutableListOf<TrackPoint>()

            file.forEachLine { line ->
                if (line.isNotBlank()) {
                    val json = JSONObject(line)
                    points.add(jsonToPoint(json))
                }
            }

            if (points.isEmpty()) return null

            val startTime = points.first().timestamp
            val endTime = points.last().timestamp
            val duration = endTime - startTime

            // Calculate total distance
            var totalDistance = 0f
            for (i in 1 until points.size) {
                val prev = points[i - 1]
                val curr = points[i]
                val results = FloatArray(1)
                Location.distanceBetween(
                    prev.latitude, prev.longitude,
                    curr.latitude, curr.longitude,
                    results
                )
                totalDistance += results[0]
            }

            TrackSession(
                sessionId = file.nameWithoutExtension,
                startTime = startTime,
                endTime = endTime,
                points = points,
                totalDistance = totalDistance,
                duration = duration
            )
        } catch (e: Exception) {
            Log.e("OfflineGPS", "Failed to parse session: ${e.message}")
            null
        }
    }
}
