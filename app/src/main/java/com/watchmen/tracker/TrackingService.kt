package com.watchmen.tracker

import android.annotation.SuppressLint
import android.Manifest
import android.app.*

import android.app.admin.DevicePolicyManager
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.*
import javax.net.ssl.*
import kotlin.math.*

// ===== ENUMS & DATA CLASSES =====
enum class TrackingState {
    INITIALIZING, MOVING, IDLE_SUSPECTED, IDLE_CONFIRMED, STOPPED, IN_VEHICLE
}

data class MovementContext(
    var speed: Float = 0f,
    var displacement: Float = 0f,
    var stepsInWindow: Int = 0,
    var consecutiveStillReadings: Int = 0,
    var consecutiveMoveReadings: Int = 0,
    var lastSignificantMove: Long = 0L,
    var totalDistance: Float = 0f,
    var totalDistanceInVehicle: Float = 0f,
    var accelVariance: Float = 0f
)
private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .build()
}

// ===== KALMAN FILTER =====
class KalmanPedestrian(
    private val qPosition: Float = 0.5f,
    private val qVelocity: Float = 0.3f,
    private val rGPS: Float = 10.0f
) {
    private var x = DoubleArray(4)
    private var p = Array(4) { DoubleArray(4) }
    private var initialized = false
    private var lastTime = 0L
    private var maxVelocity = 2.0

    fun setVehicleMode(isVehicle: Boolean) {
        maxVelocity = if (isVehicle) 40.0 else 2.0
    }

    fun process(lat: Double, lon: Double, accuracy: Float, timestamp: Long, isStationary: Boolean = false): Pair<Double, Double> {
        if (!initialized) {
            x[0] = lat; x[1] = lon; x[2] = 0.0; x[3] = 0.0
            p[0][0] = (accuracy * accuracy).toDouble()
            p[1][1] = (accuracy * accuracy).toDouble()
            p[2][2] = 1.0; p[3][3] = 1.0
            initialized = true
            lastTime = timestamp
            return Pair(lat, lon)
        }

        val dt = (timestamp - lastTime) / 1000.0
        if (dt <= 0) return Pair(x[0], x[1])
        lastTime = timestamp

        x[0] = x[0] + x[2] * dt
        x[1] = x[1] + x[3] * dt
        x[2] = x[2].coerceIn(-maxVelocity * 1e-5, maxVelocity * 1e-5)
        x[3] = x[3].coerceIn(-maxVelocity * 1e-5, maxVelocity * 1e-5)

        p[0][0] += qPosition * dt * dt
        p[1][1] += qPosition * dt * dt
        p[2][2] += qVelocity * dt
        p[3][3] += qVelocity * dt

        if (isStationary) {
            x[2] = 0.0; x[3] = 0.0
            p[2][2] *= 0.1; p[3][3] *= 0.1
        }

        val r = (accuracy * accuracy).coerceAtLeast(rGPS * rGPS).toDouble()
        val k0 = p[0][0] / (p[0][0] + r)
        val k1 = p[1][1] / (p[1][1] + r)

        x[0] += k0 * (lat - x[0])
        x[1] += k1 * (lon - x[1])
        p[0][0] *= (1 - k0)
        p[1][1] *= (1 - k1)

        return Pair(x[0], x[1])
    }
}

// ===== STATIONARY DETECTOR =====
class StationaryDetector(
    private val driftThreshold: Float = 3.0f,
    private val confirmWindow: Int = 5,
    private val velocityThreshold: Float = 0.15f
) {
    private val recentPositions = ArrayDeque<Location>(confirmWindow)
    private val lock = Any()

    fun isStationary(location: Location, steps: Int): Boolean {
        synchronized(lock) {
            recentPositions.addLast(location)
            if (recentPositions.size > confirmWindow) recentPositions.removeFirst()
            if (recentPositions.size < confirmWindow) return false

            val positionsSnapshot = recentPositions.map {
                Pair(it.latitude, it.longitude)
            }

            val center = positionsSnapshot.reduce { a, b ->
                Pair((a.first + b.first) / 2, (a.second + b.second) / 2)
            }

            val maxDrift = positionsSnapshot.maxOf { pos ->
                haversine(center.first, center.second, pos.first, pos.second)
            }

            return maxDrift < driftThreshold &&
                    location.speed < velocityThreshold &&
                    steps == 0
        }
    }
}

// ===== VEHICLE DETECTOR =====
class VehicleDetector(
    private val speedThresholdLow: Float = 7.0f,
    private val speedThresholdHigh: Float = 15.0f,
    private val confirmWindow: Int = 4
) {
    private val recentSpeeds = ArrayDeque<Float>(confirmWindow)
    private val recentAccelVariances = ArrayDeque<Float>(confirmWindow)
    private val lock = Any()

    fun isInVehicle(speed: Float, accelVariance: Float, steps: Int, displacement: Float): Boolean {
        synchronized(lock) {
            recentSpeeds.addLast(speed)
            recentAccelVariances.addLast(accelVariance)

            if (recentSpeeds.size > confirmWindow) recentSpeeds.removeFirst()
            if (recentAccelVariances.size > confirmWindow) recentAccelVariances.removeFirst()

            if (recentSpeeds.size < confirmWindow) return false

            val speedSnapshot = recentSpeeds.toList()
            val accelSnapshot = recentAccelVariances.toList()

            val avgSpeed = speedSnapshot.average().toFloat()
            val avgAccelVar = accelSnapshot.average().toFloat()

            val highSpeed = avgSpeed > speedThresholdLow
            val veryHighSpeed = avgSpeed > speedThresholdHigh
            val smoothMotion = avgAccelVar < 2.0f
            val noWalking = steps == 0
            val significantMovement = displacement > 50f

            return (highSpeed && smoothMotion && noWalking) ||
                    (veryHighSpeed && noWalking) ||
                    (significantMovement && highSpeed && noWalking)
        }
    }

    fun reset() {
        synchronized(lock) {
            recentSpeeds.clear()
            recentAccelVariances.clear()
        }
    }
}

// ===== GEOFENCE =====
data class Geofence(val id: String, val centerLat: Double, val centerLon: Double, val radiusMeters: Float, val name: String = "", val enabled: Boolean = true)
data class GeofenceConfig(val geofences: List<Geofence>, val lastUpdated: Long = System.currentTimeMillis())

class GeofenceManager(private val context: Context) {
    private val prefs = context.getSharedPreferences("geofence_prefs", Context.MODE_PRIVATE)
    private var cachedConfig: GeofenceConfig? = null

    companion object {
        private const val KEY_GEOFENCE_JSON = "geofence_config"
        private const val SYNC_INTERVAL = 300_000L
    }

    fun getGeofenceConfig(): GeofenceConfig? {
        if (cachedConfig != null) return cachedConfig
        val json = prefs.getString(KEY_GEOFENCE_JSON, null) ?: return null
        return try {
            parseGeofenceJson(json).also { cachedConfig = it }
        } catch (e: Exception) {
            Log.e("GeofenceManager", "Failed to parse cached geofences: \${e.message}")
            null
        }
    }

    fun saveGeofenceConfig(config: GeofenceConfig) {
        try {
            val json = JSONObject().apply {
                put("last_updated", config.lastUpdated)
                put("geofences", JSONArray().apply {
                    config.geofences.forEach { fence ->
                        put(JSONObject().apply {
                            put("id", fence.id)
                            put("center_lat", fence.centerLat)
                            put("center_lon", fence.centerLon)
                            put("radius_meters", fence.radiusMeters)
                            put("name", fence.name)
                            put("enabled", fence.enabled)
                        })
                    }
                })
            }.toString()
            prefs.edit().putString(KEY_GEOFENCE_JSON, json).apply()
            cachedConfig = config
            Log.i("GeofenceManager", "✅ Saved \${config.geofences.size} geofences")
        } catch (e: Exception) {
            Log.e("GeofenceManager", "Failed to save geofences: \${e.message}")
        }
    }

    suspend fun syncFromServer(deviceId: String, client: OkHttpClient): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = BackendEndpointManager.getHttpUrl("/geofence/config", mapOf("deviceid" to deviceId))
            val request = Request.Builder()
                .url(url)
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@withContext false
                    val config = parseGeofenceJson(body)
                    saveGeofenceConfig(config)
                    Log.i("GeofenceManager", "🔄 Synced \${config.geofences.size} geofences from server")
                    true
                } else {
                    Log.w("GeofenceManager", "Server returned \${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("GeofenceManager", "Sync failed: \${e.message}")
            false
        }
    }


    private fun parseGeofenceJson(json: String): GeofenceConfig {
        val obj = JSONObject(json)
        val fencesArray = obj.getJSONArray("geofences")
        val fences = mutableListOf<Geofence>()
        for (i in 0 until fencesArray.length()) {
            val fence = fencesArray.getJSONObject(i)
            val centerLat = if (fence.has("center_lat")) fence.getDouble("center_lat") else fence.optDouble("centerlat", 0.0)
            val centerLon = if (fence.has("center_lon")) fence.getDouble("center_lon") else fence.optDouble("centerlon", 0.0)
            val radius = if (fence.has("radius_meters")) fence.getDouble("radius_meters") else fence.optDouble("radiusmeters", 0.0)
            fences.add(
                Geofence(
                    id = fence.getString("id"),
                    centerLat = centerLat,
                    centerLon = centerLon,
                    radiusMeters = radius.toFloat(),
                    name = fence.optString("name", ""),
                    enabled = fence.optBoolean("enabled", true)
                )
            )
        }
        return GeofenceConfig(fences, obj.optLong("last_updated", obj.optLong("lastupdated", System.currentTimeMillis())))
    }

    fun isInsideGeofence(lat: Double, lon: Double): Pair<Boolean, String?> {
        val config = getGeofenceConfig() ?: return Pair(true, null)
        val enabledFences = config.geofences.filter { it.enabled }
        if (enabledFences.isEmpty()) return Pair(true, null)
        for (fence in enabledFences) {
            val distance = haversine(fence.centerLat, fence.centerLon, lat, lon)
            if (distance <= fence.radiusMeters) {
                return Pair(true, fence.name)
            }
        }
        return Pair(false, null)
    }

    fun shouldSync(): Boolean {
        val config = getGeofenceConfig() ?: return true
        val timeSinceSync = System.currentTimeMillis() - config.lastUpdated
        return timeSinceSync > SYNC_INTERVAL
    }
}

fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
    val earthRadius = 6371000f
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return (earthRadius * c).toFloat()
}

// ===== TELEMETRY CACHE =====
class TelemetryCache(ctx: Context) {
    private val dir = File(ctx.filesDir, "telemetry_cache").apply { mkdirs() }

    fun save(json: String) {
        try {
            val f = File(dir, "\${System.currentTimeMillis()}_\${UUID.randomUUID()}.json")
            f.writeText(json)
        } catch (e: Exception) {
            Log.e("Cache", "Save failed: \${e.message}")
        }
    }

    fun list(): List<File> = try {
        dir.listFiles()?.sortedBy { it.name } ?: emptyList()
    } catch (_: Exception) { emptyList() }

    fun read(file: File): String? = try {
        BufferedReader(FileReader(file)).use { it.readText() }
    } catch (e: Exception) { null }

    fun remove(file: File) {
        try { file.delete() } catch (_: Exception) {}
    }

    fun count(): Int = list().size

    fun clear() {
        list().forEach { remove(it) }
    }
}

@Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
fun trustAllCertificates() {
    if (!BuildConfig.DEBUG) {
        Log.i("Watchmen", "🔒 Release build: Certificate verification is strictly enforced.")
        return
    }
    try {
        val trustAllCerts = arrayOf<TrustManager>(
            object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
        )
        val sc = SSLContext.getInstance("SSL")
        sc.init(null, trustAllCerts, SecureRandom())
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
        HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
        Log.w("Watchmen", "⚠️ Debug build: trustAllCertificates enabled for local dev testing.")
    } catch (e: Exception) {
        Log.e("Watchmen", "Failed to init debug SSL trust manager: ${e.message}")
    }
}

// ===== MAIN TRACKING SERVICE =====
class TrackingService : Service(), SensorEventListener {
    private lateinit var wakeLock: PowerManager.WakeLock
    private lateinit var fusedClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var cache: TelemetryCache
    private lateinit var geofenceManager: GeofenceManager
    private lateinit var ttsHelper: MultilingualTTS
    private var currentLanguage: String = "en"
    private lateinit var warningCooldown: WarningCooldownManager  // ✅ ADD THIS
    private var locationCallback: LocationCallback? = null

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder()
        .pingInterval(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    private var webSocket: WebSocket? = null
    @Volatile private var webSocketConnected = false
    private val isConnecting = java.util.concurrent.atomic.AtomicBoolean(false)
    private val reconnectAttempt = java.util.concurrent.atomic.AtomicInteger(0)
    private var reconnectJob: Job? = null
    @Volatile private var isIntentionalServiceStop = false
    private var discoveryManager: BackendDiscoveryManager? = null
    private var lastStepsForAuth = 0


    private var currentState = TrackingState.INITIALIZING
    private var movementContext = MovementContext()
    private val kalman = KalmanPedestrian()
    private val stationaryDetector = StationaryDetector()
    private val vehicleDetector = VehicleDetector()

    // ✅ NEW SECURITY MODULES
    private lateinit var gpsSpoofingDetector: GpsSpoofingDetector
    private lateinit var movementAuthDetector: MovementAuthenticityDetector
    private lateinit var simCardMonitor: SimCardMonitor
    private lateinit var offlineGpsStorage: OfflineGpsStorage
    private lateinit var sirenController: SirenController

    private var lastKnownLocation: Location? = null
    private var lastSentLocation: Location? = null
    private var lastSentTime = 0L
    private var stepsSinceLastUpdate = 0
    private var lastHeartbeat = 0L

    // Data delay detection
    private var lastServerResponseTime = 0L
    private var consecutiveFailures = 0
    private var lastDataSendTime = 0L

    private val accelWindow = ArrayDeque<Float>(20)
    private val accelLock = Any()

    private var currentUpdateInterval = 20_000L
    private val intervalMoving = 20_000L
    private val intervalIdleSuspected = 20_000L
    private val intervalIdleConfirmed = 30_000L
    private val intervalStopped = 60_000L
    private val intervalVehicle = 20_000L
    private val speedThresholdMoving = 0.3f
    private val displacementThreshold = 3.0f
    private val idleConfirmCount = 2
    private val forceSendInterval = 20_000L

    override fun onBind(intent: Intent?): IBinder? = null

    private fun calculateBackoffDelay(attempt: Int): Long {
        val base = 1000L
        val factor = 1.5
        val maxDelay = 30000L
        val exponential = (base * Math.pow(factor, attempt.coerceAtMost(10).toDouble())).toLong()
        val capped = minOf(exponential, maxDelay)
        val jitter = (Math.random() * 500).toLong()
        return capped + jitter
    }

    @Synchronized
    private fun scheduleWebSocketReconnect(forcedDelayMs: Long? = null) {
        if (isIntentionalServiceStop) {
            Log.d("Watchmen", "[WS] Reconnect ignored: intentional service stop")
            return
        }

        reconnectJob?.cancel()

        val attempt = reconnectAttempt.incrementAndGet()
        val delayMs = forcedDelayMs ?: calculateBackoffDelay(attempt)
        Log.i("Watchmen", "[WS] Reconnect scheduled in ${delayMs}ms (attempt $attempt)")

        reconnectJob = serviceScope.launch {
            delay(delayMs)
            if (!isActive || isIntentionalServiceStop) return@launch

            if (!BackendEndpointManager.isConfigured()) {
                Log.i("Watchmen", "[WS] Endpoint not configured, triggering discovery...")
                discoveryManager?.startDiscovery()
            }

            connectWebSocket()
        }
    }

    private fun reconnectWebSocketToNewEndpoint() {
        Log.i("Watchmen", "[WS] Reconnecting to new endpoint")
        reconnectJob?.cancel()
        reconnectAttempt.set(0)
        try {
            webSocket?.close(1000, "Endpoint changed")
        } catch (e: Exception) {
            Log.w("Watchmen", "[WS] Error closing WebSocket: ${e.message}")
        }
        webSocket = null
        webSocketConnected = false
        isConnecting.set(false)
        connectWebSocket()
    }

    private fun connectWebSocket() {
        if (isIntentionalServiceStop) return

        if (!isConnecting.compareAndSet(false, true)) {
            Log.d("Watchmen", "[WS] Connection already in progress, skipping duplicate attempt")
            return
        }

        try {
            if (webSocketConnected && webSocket != null) {
                Log.d("Watchmen", "[WS] Already connected")
                isConnecting.set(false)
                return
            }

            val deviceId = getTrackerDeviceId()
            val wsUrl = BackendEndpointManager.getWebSocketUrlOrNull("/ws", mapOf("type" to "device", "deviceid" to deviceId))
            if (wsUrl == null) {
                Log.w("Watchmen", "[WS] Backend endpoint not yet resolved; WebSocket waiting for discovery...")
                isConnecting.set(false)
                discoveryManager?.startDiscovery()
                scheduleWebSocketReconnect()
                return
            }

            try {
                webSocket?.close(1000, "Opening new connection")
            } catch (_: Exception) {}
            webSocket = null

            val request = Request.Builder()
                .url(wsUrl)
                .build()

            Log.i("Watchmen", "[WS] Connecting to $wsUrl | deviceId: $deviceId")

            webSocket = client.newWebSocket(
                request,
                object : WebSocketListener() {

                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response
                    ) {
                        isConnecting.set(false)
                        webSocketConnected = true
                        reconnectAttempt.set(0)
                        reconnectJob?.cancel()

                        Log.i("Watchmen", "[WS] Connected | Device: $deviceId")

                        // Send device handshake so backend registers device connection immediately
                        try {
                            val handshake = JSONObject().apply {
                                put("type", "device_handshake")
                                put("deviceid", deviceId)
                                put("device_id", deviceId)
                                put("timestamp", System.currentTimeMillis())
                            }.toString()
                            webSocket.send(handshake)
                            Log.i("Watchmen", "[WS] Device registered | Device: $deviceId")
                        } catch (e: Exception) {
                            Log.e("Watchmen", "[WS] Failed to send WS handshake: ${e.message}")
                        }
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String
                    ) {
                        Log.i(
                            "Watchmen",
                            "📩 WebSocket message: $text"
                        )
                        handleWebSocketMessage(webSocket, text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        webSocketConnected = false

                        Log.w("Watchmen", "[WS] Closing: $code - $reason")

                        webSocket.close(code, reason)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String
                    ) {
                        webSocketConnected = false
                        isConnecting.set(false)

                        Log.w("Watchmen", "[WS] Closed: $code - $reason")

                        if (!isIntentionalServiceStop) {
                            scheduleWebSocketReconnect()
                        }
                    }

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?
                    ) {
                        webSocketConnected = false
                        isConnecting.set(false)

                        Log.e("Watchmen", "[WS] Failure: ${t.message} | HTTP response code: ${response?.code}", t)

                        if (!isIntentionalServiceStop) {
                            scheduleWebSocketReconnect()
                        }
                    }
                }
            )

        } catch (e: Exception) {
            isConnecting.set(false)
            Log.e("Watchmen", "[WS] Connection setup exception: ${e.message}", e)
            if (!isIntentionalServiceStop) {
                scheduleWebSocketReconnect()
            }
        }
    }

    private fun handleWebSocketMessage(webSocket: WebSocket, text: String) {
        try {
            val json = JSONObject(text)
            val type = json.optString("type")
            val deviceId = getTrackerDeviceId()

            when (type) {
                "command" -> {
                    val command = json.optString("command")
                    Log.i("Watchmen", "⚡ Executing remote command: $command")
                    handleRemoteCommand(command, webSocket)
                }

                "message" -> {
                    val messageText = json.optString("message")
                    val urgent = json.optBoolean("urgent", true)
                    Log.i("Watchmen", "💬 Remote message received: $messageText")

                    if (::ttsHelper.isInitialized && messageText.isNotBlank()) {
                        ttsHelper.speak(messageText, currentLanguage, urgent = urgent)
                    }

                    val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createOneShot(500, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator?.vibrate(500)
                    }

                    val ack = JSONObject().apply {
                        put("type", "message_ack")
                        put("deviceid", deviceId)
                        put("device_id", deviceId)
                        put("status", "success")
                        put("timestamp_uae", toUaeTimestamp())
                    }.toString()
                    webSocket.send(ack)
                }

                "announcement" -> {
                    val ann = Announcement(
                        title = json.optString("title", "Announcement"),
                        message = json.optString("message", ""),
                        priority = json.optString("priority", "NORMAL"),
                        tts = json.optBoolean("tts", true),
                        vibrate = json.optBoolean("vibrate", false),
                        raise_alert = json.optBoolean("raise_alert", false),
                        language = json.optString("language", currentLanguage)
                    )
                    handleAnnouncement(ann)
                }

                else -> {
                    Log.d("Watchmen", "ℹ️ Received unhandled WS message type: $type")
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Error parsing WS message: ${e.message}", e)
        }
    }

    private fun handleRemoteCommand(command: String, ws: WebSocket?) {
        var status = "success"
        var details = ""

        when (command) {
            "START_SIREN" -> {
                val ok = sirenController.startSiren()
                status = if (ok) "success" else "error"
                details = if (ok) "Siren and vibration activated" else "Failed to start siren"
            }

            "STOP_SIREN" -> {
                val ok = sirenController.stopSiren()
                status = if (ok) "success" else "error"
                details = if (ok) "Siren and vibration stopped" else "Failed to stop siren"
            }

            "GET_LOCATION" -> {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    fusedClient.lastLocation.addOnSuccessListener { loc ->
                        if (loc != null) {
                            sendTelemetry(loc)
                            sendLocationAck(ws, command, loc, "success", "Location dispatched to backend")
                        } else {
                            sendCommandAck(ws, command, "error", "Location currently unavailable")
                        }
                    }.addOnFailureListener { e ->
                        sendCommandAck(ws, command, "error", "Failed to retrieve location: ${e.message}")
                    }
                    return // ACK handled asynchronously in callbacks
                } else {
                    status = "error"
                    details = "Location permission denied"
                }
            }

            "WIPE_CACHE" -> {
                try {
                    cache.clear()
                    offlineGpsStorage.clearAllSessions()
                    status = "success"
                    details = "Telemetry and offline GPS cache cleared"
                    Log.i("Watchmen", "🗑️ Cache wiped successfully")
                } catch (e: Exception) {
                    status = "error"
                    details = "Cache wipe failed: ${e.message}"
                }
            }

            "RESTART_SERVICE" -> {
                Log.i("Watchmen", "[SERVICE] Restart requested via remote command")
                sendCommandAck(ws, command, "success", "Restarting tracking service")
                serviceScope.launch {
                    delay(500)
                    restartService()
                }
                return
            }

            else -> {
                Log.w("Watchmen", "❓ Unknown remote command: $command")
                status = "error"
                details = "Unknown command: $command"
            }
        }

        sendCommandAck(ws, command, status, details)
    }

    private fun sendCommandAck(ws: WebSocket?, command: String, status: String, details: String) {
        try {
            val ack = JSONObject().apply {
                put("type", "command_ack")
                put("deviceid", getTrackerDeviceId())
                put("device_id", getTrackerDeviceId())
                put("command", command)
                put("status", status)
                put("details", details)
                put("timestamp_uae", toUaeTimestamp())
            }.toString()
            val targetWs = ws ?: webSocket
            targetWs?.send(ack)
            Log.i("Watchmen", "📤 Sent command ACK: $ack")
        } catch (e: Exception) {
            Log.e("Watchmen", "Failed to send command ACK: ${e.message}")
        }
    }

    private fun sendLocationAck(ws: WebSocket?, command: String, loc: Location, status: String, details: String) {
        try {
            val ack = JSONObject().apply {
                put("type", "command_ack")
                put("deviceid", getTrackerDeviceId())
                put("device_id", getTrackerDeviceId())
                put("command", command)
                put("status", status)
                put("latitude", loc.latitude)
                put("longitude", loc.longitude)
                put("details", details)
                put("timestamp_uae", toUaeTimestamp())
            }.toString()
            val targetWs = ws ?: webSocket
            targetWs?.send(ack)
            Log.i("Watchmen", "📤 Sent location ACK: $ack")
        } catch (e: Exception) {
            Log.e("Watchmen", "Failed to send location ACK: ${e.message}")
        }
    }

    private fun toUaeTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("GMT+4")
        }
        return sdf.format(Date())
    }

    override fun onCreate() {
        super.onCreate()
        Log.i("Watchmen", "[SERVICE] TrackingService onCreate")
        startForeground(1, createNotification())

        isIntentionalServiceStop = false

        Log.i("Watchmen", "WS DEBUG: onCreate starting BackendEndpointManager.init")
        BackendEndpointManager.init(applicationContext)

        BackendEndpointManager.addListener(object : BackendEndpointManager.EndpointChangeListener {
            override fun onEndpointChanged(newHttpUrl: String, newWsUrl: String) {
                Log.i("Watchmen", "WS DEBUG: EndpointChangeListener fired! newHttpUrl=$newHttpUrl | newWsUrl=$newWsUrl")
                Log.i("Watchmen", "🔄 Backend endpoint resolved/changed! Reconnecting WebSocket to $newWsUrl...")
                reconnectWebSocketToNewEndpoint()
                serviceScope.launch {
                    syncCachedTelemetry()
                    syncOfflineSessions()
                }
            }
        })

        discoveryManager = BackendDiscoveryManager(this)
        Log.i("Watchmen", "[DISCOVERY] Starting discovery")
        discoveryManager?.startDiscovery()

        if (BuildConfig.DEBUG) {
            try {
                ContextCompat.registerReceiver(
                    this,
                    object : BroadcastReceiver() {
                        override fun onReceive(context: Context?, intent: Intent?) {
                            val url = intent?.getStringExtra("url")
                            Log.i("Watchmen", "WS DEBUG: Received SET_ENDPOINT broadcast with url: $url")
                            if (!url.isNullOrBlank()) {
                                BackendEndpointManager.updateEndpoint(url, applicationContext)
                            }
                        }
                    },
                    IntentFilter("com.watchmen.tracker.SET_ENDPOINT"),
                    ContextCompat.RECEIVER_EXPORTED
                )
            } catch (e: Exception) {
                Log.w("Watchmen", "Failed to register SET_ENDPOINT receiver: ${e.message}")
            }
        }

        acquireWakeLock()
        installCrashHandler()
        trustAllCertificates()

        try {
            fusedClient = LocationServices.getFusedLocationProviderClient(this)
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            cache = TelemetryCache(this)
            geofenceManager = GeofenceManager(this)

            // ✅ Initialize NEW security modules
            gpsSpoofingDetector = GpsSpoofingDetector()
            movementAuthDetector = MovementAuthenticityDetector(sensorManager)
            simCardMonitor = SimCardMonitor(this)
            offlineGpsStorage = OfflineGpsStorage(this)
            sirenController = SirenController(this)
            startOfflineSyncLoop()
            warningCooldown = WarningCooldownManager()  // ✅ ADD THIS


            movementAuthDetector.startMonitoring()

            // ✅ Initialize TTS
            initializeTTS()

            // ✅ Get user's language preference
            currentLanguage = getPreferredLanguage()

            registerStepSensor()
            registerAccelerometerSensor()
            Log.i("Watchmen", "WS DEBUG: onCreate invoking connectWebSocket()")
            connectWebSocket()
            startContinuousLocationUpdates()
            scheduleRetryLoop()
            scheduleStateEvaluation()
            scheduleGeofenceSync()
            scheduleHeartbeat()
            scheduleJobService()
            scheduleSecurityChecks()  // ✅ NEW
            createAnnouncementChannel()
            startTenSecondLoop()

            Log.i("Watchmen", "✅ Service READY: $currentState")
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Init failed: ${e.message}", e)
            Handler(Looper.getMainLooper()).postDelayed({ restartService() }, 3000)
        }
    }



    private fun createAnnouncementChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ANNOUNCEMENTS",
                "Control Center Announcements",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent announcements from DWEX"
                enableVibration(true)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ✅ NEW: Initialize TTS
    private fun initializeTTS() {
        ttsHelper = MultilingualTTS(this)
        ttsHelper.initialize(
            onSuccess = {
                Log.i("Watchmen", "✅ TTS ready in service")
            },
            onError = {
                Log.e("Watchmen", "❌ TTS failed to initialize in service")
            }
        )
    }

    // ✅ NEW: Get user's language preference
    private fun getPreferredLanguage(): String {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        return prefs.getString("app_language", "en") ?: "en"
    }

    // ✅ NEW: Security checks
    // ✅ UPDATE: Security checks with cooldown
    private fun scheduleSecurityChecks() {
        serviceScope.launch {
            while (isActive) {
                try {
                    // Check SIM card status
                    val simStatus = simCardMonitor.getCurrentStatus()
                    if (!simStatus.isPresent) {
                        sendSecurityAlert("sim_removed", "SIM card removed")

                        // ✅ Only announce if not in cooldown
                        if (warningCooldown.canAnnounce(
                                "sim_removed",
                                WarningCooldownManager.COOLDOWN_SIM_REMOVED
                            )) {
                            ttsHelper.speak(
                                "Warning: SIM card has been removed",
                                currentLanguage,
                                urgent = true
                            )
                        }
                    }

                    // Check movement authenticity
                    val stepDeltaForAuth = stepsSinceLastUpdate - lastStepsForAuth
                    lastStepsForAuth = stepsSinceLastUpdate

                    val authResult = movementAuthDetector.analyzeMovement(stepDeltaForAuth.coerceAtLeast(0))

                    if (!authResult.isAuthentic && authResult.suspiciousActivity != null) {

                        sendSecurityAlert("fake_movement", authResult.suspiciousActivity)

                        if (warningCooldown.canAnnounce(
                                "fake_movement",
                                WarningCooldownManager.COOLDOWN_FAKE_MOVEMENT
                            )
                        ) {
                            Log.w("Watchmen", "🔊 ANNOUNCING FAKE MOVEMENT")
                            ttsHelper.speak(
                                "Unusual movement detected. Please carry the phone normally.",
                                currentLanguage,
                                urgent = true
                            )
                        }
                    }



                    // Check data delay
                    if (detectDataDelay()) {
                        // ✅ Only announce if not in cooldown
                        if (warningCooldown.canAnnounce(
                                "data_delay",
                                WarningCooldownManager.COOLDOWN_DATA_DELAY
                            )) {
                            ttsHelper.speak(
                                "Warning: Server connection delay detected",
                                currentLanguage,
                                urgent = true
                            )
                        }
                    }

                } catch (e: Exception) {
                    Log.e("Watchmen", "Security check error: ${e.message}")
                }
                delay(10_000) // Check every 10 seconds
            }
        }
    }
    private fun startTenSecondLoop() {
        serviceScope.launch {
            while (isActive) {
                checkForLocationRequest()
                checkForAnnouncements()
                delay(25_000)
            }
        }
    }

    private fun sendStateBroadcast() {
        val intent = Intent("TRACKING_STATE_UPDATE")
        intent.putExtra("state", currentState.name)
        sendBroadcast(intent)
    }
    private suspend fun checkForLocationRequest() {
        try {
            val url = BackendEndpointManager.getHttpUrl("/check-location-request", mapOf("device_id" to getTrackerDeviceId()))

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val json = JSONObject(response.body!!.string())
                val shouldSend = json.optBoolean("request", false)

                if (shouldSend) {
                    Log.i("Watchmen", "📡 Live location request received from dashboard!")
                    requestImmediateLocation()
                }
            }

            response.close()
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Error checking live request: ${e.message}")
        }
    }

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    private fun requestImmediateLocation() {
        if (
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e("Watchmen", "❌ Location permission missing for immediate request")
            return
        }

        try {
            fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).addOnSuccessListener { location ->
                if (location != null) {
                    Log.i("Watchmen", "📍 Sending IMMEDIATE location update")
                    sendTelemetry(location)
                } else {
                    Log.w("Watchmen", "⚠️ No location available for immediate request")
                }
            }.addOnFailureListener {
                Log.e("Watchmen", "❌ Failed immediate location: ${it.message}")
            }
        } catch (e: SecurityException) {
            Log.e("Watchmen", "❌ SecurityException: ${e.message}")
        }
    }




    private fun detectDataDelay(): Boolean {
        val now = System.currentTimeMillis()
        val timeSinceLastSend = now - lastDataSendTime
        val timeSinceLastResponse = now - lastServerResponseTime

        if (timeSinceLastSend < 60000 && timeSinceLastResponse > 30000) {
            consecutiveFailures++

            if (consecutiveFailures > 3) {
                Log.e("Watchmen", "🚨 DATA DELAY DETECTED! No server response for \${timeSinceLastResponse / 1000}s")
                sendSecurityAlert("data_delay", "No server response for \${timeSinceLastResponse / 1000}s")
                return true
            }
        } else {
            consecutiveFailures = 0
        }

        return false
    }

    private fun sendSecurityAlert(alertType: String, details: String) {
        val json = JSONObject().apply {
            put("type", "security_alert")  // ✅ ADD THIS - Tag for routing
            put("deviceid", getTrackerDeviceId())
            put("alerttype", alertType)
            put("details", details)
            put("timestamp", System.currentTimeMillis())
        }.toString()

        serviceScope.launch {
            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/security-alert"))
                    .post(body)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    lastServerResponseTime = System.currentTimeMillis()  // ✅ Update response time
                    Log.d("Watchmen", "✅ Security alert sent: $alertType")
                } else {
                    Log.e("Watchmen", "❌ Security alert HTTP ${response.code}")
                }
                response.close()
            } catch (e: Exception) {
                Log.e("Watchmen", "❌ Security alert error: ${e.message}")
                cache.save(json)
            }
        }
    }


    private fun scheduleHeartbeat() {
        serviceScope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                lastHeartbeat = now
                val gpsAge = lastKnownLocation?.time?.let { now - it } ?: -1
                Log.i("Watchmen", "💓 HEARTBEAT | State: $currentState | GPS: ${gpsAge}ms ago | Cache: ${cache.count()} | WS: $webSocketConnected")

                if (!webSocketConnected && !isConnecting.get() && !isIntentionalServiceStop) {
                    Log.w("Watchmen", "[WS] Heartbeat watchdog: WebSocket not connected, scheduling reconnect")
                    scheduleWebSocketReconnect()
                }

                delay(30_000)
            }
        }
    }

    private fun scheduleAggressiveRestart() {
        val intent = Intent(applicationContext, TrackingService::class.java)
        val pendingIntent = PendingIntent.getService(
            applicationContext,
            999,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val triggerTime = SystemClock.elapsedRealtime() + 60_000

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        } else {
            alarmManager.setExact(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                triggerTime,
                pendingIntent
            )
        }

        Log.i("Watchmen", "⏰ Aggressive restart alarm scheduled")
    }

    private fun scheduleJobService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            val jobScheduler = getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
            @SuppressLint("MissingPermission")

            val job = JobInfo.Builder(456, ComponentName(this, TrackingJobService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(15 * 60 * 1000L)
                .setPersisted(true)
                .build()

            jobScheduler.schedule(job)
            Log.i("Watchmen", "📅 JobScheduler backup registered")
        }
    }
    private fun handleAnnouncement(a: Announcement) {

        // 🔔 Notification
        showAnnouncementNotification(a)

        // 📳 Vibration
        if (a.vibrate) {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(
                    VibrationEffect.createOneShot(
                        800,
                        VibrationEffect.DEFAULT_AMPLITUDE
                    )
                )
            } else {
                vibrator.vibrate(800)
            }
        }

        // 🔊 Voice
        if (a.tts) {
            ttsHelper.speak(
                a.message,
                a.language,
                urgent = a.priority == "HIGH" || a.priority == "CRITICAL"
            )
        }

        // 🚨 Optional ACK back to server
        if (a.raise_alert) {
            sendAnnouncementAck(a)
        }
    }
    private fun showAnnouncementNotification(a: Announcement) {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, "ANNOUNCEMENTS")
            .setSmallIcon(R.drawable.ic_alert)
            .setContentTitle(a.title)
            .setContentText(a.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(System.currentTimeMillis().toInt(), builder.build())
    }
    private fun sendAnnouncementAck(a: Announcement) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val json = JSONObject().apply {
                    put("device_id", getTrackerDeviceId())
                    put("alert_type", "ANNOUNCEMENT_ACK")
                    put("details", a.message)
                    put("timestamp", System.currentTimeMillis())
                }

                val body = json.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/security-alert"))
                    .post(body)
                    .build()

                OkHttpClient().newCall(request).execute()
            } catch (e: Exception) {
                Log.e("Watchmen", "Failed to ACK announcement: ${e.message}")
            }
        }
    }
    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "Watchmen::TrackingWakeLock"
            )
            wakeLock.acquire(10 * 60 * 1000L)
            Log.i("Watchmen", "🔋 Wake lock acquired")

            serviceScope.launch {
                while (isActive) {
                    delay(8 * 60 * 1000L)
                    try {
                        if (wakeLock.isHeld) {
                            wakeLock.release()
                        }
                        wakeLock.acquire(10 * 60 * 1000L)
                        Log.d("Watchmen", "🔋 Wake lock refreshed")
                    } catch (e: Exception) {
                        Log.e("Watchmen", "Wake lock refresh failed: \${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "Failed to acquire wake lock: \${e.message}")
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Log.e("Watchmen", "💥 CRASH: \${throwable.message}", throwable)
            try {
                val crashReport = JSONObject().apply {
                    put("type", "crash")  // 👈 IMPORTANT
                    put("device_id", getTrackerDeviceId())
                    put("crash_time", System.currentTimeMillis())
                    put("error", throwable.message ?: "Unknown error")
                    put("stacktrace", throwable.stackTraceToString())
                }
                try {
                    val crashClient = OkHttpClient()
                    val body = crashReport.toString().toRequestBody("application/json".toMediaType())
                    val request = Request.Builder()
                        .url(BackendEndpointManager.getHttpUrl("/crash"))
                        .post(body)
                        .build()
                    crashClient.newCall(request).execute().close()
                } catch (e: Exception) {
                    cache.save(crashReport.toString())
                }
            } catch (e: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun restartService() {
        try {
            Log.w("Watchmen", "[SERVICE] Restart requested")

            if (::sirenController.isInitialized) {
                sirenController.stopSiren()
            }

            try {
                webSocket?.close(1000, "Service restart requested")
            } catch (e: Exception) {
                Log.w("Watchmen", "[WS] Error closing socket during restart: ${e.message}")
            }
            webSocket = null
            webSocketConnected = false
            isConnecting.set(false)

            currentState = TrackingState.INITIALIZING
            movementContext = MovementContext()
            vehicleDetector.reset()
            updateNotification()
            sendStateBroadcast()

            discoveryManager?.startDiscovery()

            reconnectAttempt.set(0)
            reconnectJob?.cancel()
            scheduleWebSocketReconnect(forcedDelayMs = 1000L)

            startContinuousLocationUpdates()

            Log.i("Watchmen", "[SERVICE] TrackingService reinitialized successfully")
        } catch (e: Exception) {
            Log.e("Watchmen", "[SERVICE] Restart failed: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i("Watchmen", "📍 Service command received")
        if (!webSocketConnected && !isConnecting.get() && !isIntentionalServiceStop) {
            Log.i("Watchmen", "[WS] onStartCommand: WebSocket not connected, scheduling reconnect")
            scheduleWebSocketReconnect(forcedDelayMs = 500L)
        }
        return START_STICKY
    }

    private suspend fun checkForAnnouncements() {
        try {
            val url = BackendEndpointManager.getHttpUrl("/check-announcements", mapOf("device_id" to getTrackerDeviceId()))

            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return

                val body = response.body?.string() ?: return
                val json = JSONObject(body)

                if (json.optBoolean("has_announcement", false)) {
                    val announcement = Announcement(
                        title = json.optString("title"),
                        message = json.optString("message"),
                        priority = json.optString("priority", "NORMAL"),
                        tts = json.optBoolean("tts", true),
                        vibrate = json.optBoolean("vibrate", false),
                        raise_alert = json.optBoolean("raise_alert", false),
                        language = json.optString("language", currentLanguage)
                    )

                    handleAnnouncement(announcement)
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Announcement check failed: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w("Watchmen", "⚠️ Task removed")
        super.onTaskRemoved(rootIntent)
    }


    override fun onDestroy() {
        isIntentionalServiceStop = true
        Log.w("Watchmen", "[SERVICE] TrackingService onDestroy")
        try {
            reconnectJob?.cancel()
            reconnectJob = null

            if (::wakeLock.isInitialized && wakeLock.isHeld) {
                wakeLock.release()
            }

            if (::sirenController.isInitialized) {
                sirenController.stopSiren()
            }
            webSocket?.close(1000, "Service destroyed")
            webSocket = null
            webSocketConnected = false
            serviceScope.cancel()
            offlineSyncJob?.cancel()
            offlineSyncJob = null
            sensorManager.unregisterListener(this)
            movementAuthDetector.stopMonitoring()
            ttsHelper.shutdown()  // ✅ NEW: Cleanup TTS
            locationCallback?.let { fusedClient.removeLocationUpdates(it) }
            locationCallback = null
            discoveryManager?.stopDiscovery()
        } catch (e: Exception) {
            Log.e("Watchmen", "Cleanup error: ${e.message}")
        }
        super.onDestroy()
    }

    private fun startContinuousLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Log.e("Watchmen", "❌ No location permission")
            return
        }

        val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5_000L)
            .setMinUpdateIntervalMillis(3_000L)
            .setMaxUpdateDelayMillis(8_000L)
            .setWaitForAccurateLocation(false)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { loc ->
                    Log.d("Watchmen", "📍 GPS: ${loc.latitude}, ${loc.longitude} | Acc: ${loc.accuracy}m")
                    processLocationUpdate(loc)
                }
            }
        }

        fusedClient.requestLocationUpdates(req, locationCallback!!, Looper.getMainLooper())



    }

    private fun processLocationUpdate(loc: Location) {
        // ✅ Check for GPS spoofing
        val spoofResult = gpsSpoofingDetector.detectSpoofing(loc, lastKnownLocation, this)
        if (spoofResult.isSpoofed) {
            Log.e("Watchmen", "🚨 GPS SPOOFING DETECTED!")
            sendSecurityAlert("gps_spoofing", spoofResult.reasons.joinToString(", "))
            // ✅ Announce GPS spoofing
            if (warningCooldown.canAnnounce(
                    "gps_spoofing",
                    WarningCooldownManager.COOLDOWN_GPS_SPOOFING
                )) {
                ttsHelper.speak(
                    "Critical warning: GPS spoofing detected",
                    currentLanguage,
                    urgent = true
                )
            }
            return // Don't process spoofed location
        }

        if (!validateLocation(loc)) return

        val isStationary = stationaryDetector.isStationary(loc, stepsSinceLastUpdate)
        val (smoothLat, smoothLon) = kalman.process(
            loc.latitude, loc.longitude, loc.accuracy,
            System.currentTimeMillis(), isStationary
        )

        val smoothLoc = Location(loc).apply {
            latitude = smoothLat
            longitude = smoothLon
        }

        updateMovementContext(smoothLoc)

        // ✅ Save to offline storage
        offlineGpsStorage.saveLocationPoint(smoothLoc, currentState.name)

        if (shouldSendUpdate(smoothLoc)) {
            sendTelemetry(smoothLoc)
            lastSentLocation = smoothLoc
            lastSentTime = System.currentTimeMillis()
        }

        lastKnownLocation = smoothLoc
    }

    private fun updateMovementContext(loc: Location) {
        movementContext.speed = if (loc.hasSpeed()) loc.speed else 0f

        lastSentLocation?.let { lastSent ->
            val distance = FloatArray(1)
            Location.distanceBetween(
                lastSent.latitude, lastSent.longitude,
                loc.latitude, loc.longitude,
                distance
            )

            val rawDisplacement = distance[0]
            val noiseGate = maxOf(loc.accuracy, lastSent.accuracy, 5.0f)
            val isLikelyRealMovement = (
                    rawDisplacement > noiseGate * 1.5f ||
                            (rawDisplacement > 3.0f && movementContext.stepsInWindow > 3) ||
                            movementContext.speed > 0.5f
                    )

            movementContext.displacement = if (isLikelyRealMovement) rawDisplacement else 0f

            if (isLikelyRealMovement) {
                movementContext.totalDistance += rawDisplacement
                if (currentState == TrackingState.IN_VEHICLE) {
                    movementContext.totalDistanceInVehicle += rawDisplacement
                }
            }
        } ?: run {
            movementContext.displacement = 0f
        }

        movementContext.stepsInWindow = stepsSinceLastUpdate
    }

    private fun shouldSendUpdate(loc: Location): Boolean {
        val timeSinceLastSend = System.currentTimeMillis() - lastSentTime

        if (timeSinceLastSend >= forceSendInterval) {
            Log.i("Watchmen", "⏰ Force send (${timeSinceLastSend / 1000}s)")
            return true
        }

        return when (currentState) {
            TrackingState.INITIALIZING -> true
            TrackingState.MOVING -> {
                val significantMove = movementContext.displacement > displacementThreshold && (
                        movementContext.speed > speedThresholdMoving || stepsSinceLastUpdate > 2
                        )
                val definiteMove = (
                        movementContext.displacement > 8.0f ||
                                movementContext.speed > 1.0f ||
                                stepsSinceLastUpdate > 10
                        )
                significantMove || definiteMove || timeSinceLastSend >= intervalMoving
            }
            TrackingState.IN_VEHICLE -> movementContext.displacement > 50f || timeSinceLastSend >= intervalVehicle
            TrackingState.IDLE_SUSPECTED -> movementContext.displacement > 5.0f || timeSinceLastSend >= intervalIdleSuspected
            TrackingState.IDLE_CONFIRMED -> movementContext.displacement > 8.0f || timeSinceLastSend >= intervalIdleConfirmed
            TrackingState.STOPPED -> movementContext.displacement > 10.0f || timeSinceLastSend >= intervalStopped
        }
    }

    private fun validateLocation(loc: Location): Boolean {
        // ✅ TEMPORARY FIX: Allow all locations (bypass geofence check)
        // This lets GPS telemetry flow to the server immediately
        // Once dashboard is working, you can re-enable geofence validation

        /*
        // Original geofence check (commented out for now)
        val (isInside, fenceName) = geofenceManager.isInsideGeofence(loc.latitude, loc.longitude)

        if (!isInside && currentState != TrackingState.IN_VEHICLE) {
            Log.w("Watchmen", "⚠️ GPS outside geofences")
            sendGeofenceViolationAlert(loc)
            if (warningCooldown.canAnnounce(
                    "geofence_violation",
                    WarningCooldownManager.COOLDOWN_GEOFENCE
                )) {
                ttsHelper.speak(
                    "Warning: You are outside the allowed area",
                    currentLanguage,
                    urgent = true
                )
            }
            return false
        } else if (fenceName != null) {
            Log.d("Watchmen", "✅ Inside geofence: $fenceName")
        }
        */

        // GPS jump detection (keep this active)
        lastKnownLocation?.let { last ->
            val timeDelta = (System.currentTimeMillis() - last.time) / 1000f
            if (timeDelta in 0.1f..5f) {
                val jumpDist = haversine(last.latitude, last.longitude, loc.latitude, loc.longitude)
                val maxJump = if (currentState == TrackingState.IN_VEHICLE) 100f else 10f
                if (jumpDist > maxJump) {
                    Log.w("Watchmen", "⚠️ GPS jump rejected: ${jumpDist}m in ${timeDelta}s")
                    return false
                }
            }
        }

        return true  // ✅ Allow location
    }
// ------ OFFLINE BATCH SYNC ------

    private var offlineSyncJob: Job? = null

    private fun startOfflineSyncLoop() {
        if (offlineSyncJob != null) return

        offlineSyncJob = serviceScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    syncOfflineSessions()
                } catch (e: Exception) {
                    Log.e("Watchmen", "Offline sync error: ${e.message}")
                }
                delay(60_000L) // every 60 seconds
            }
        }
    }

    private fun syncOfflineSessions() {
        if (!isOnline() || !BackendEndpointManager.isConfigured()) return

        val sessions = offlineGpsStorage.getAllSessions()
        if (sessions.isEmpty()) return

        val deviceId = getTrackerDeviceId()

        for (session in sessions) {
            val pointsArray = JSONArray().apply {
                session.points.forEach { p ->
                    put(JSONObject().apply {
                        put("lat", p.latitude)
                        put("lon", p.longitude)
                        put("ts", p.timestamp)
                        put("acc", p.accuracy)
                        put("spd", p.speed)
                        put("brg", p.bearing)
                        put("st", p.state)
                    })
                }
            }

            val requestBody = pointsArray
                .toString()
                .toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url(
                    BackendEndpointManager.getHttpUrl(
                        "/telemetry_batch",
                        mapOf("device_id" to deviceId)
                    )
                )
                .post(requestBody)
                .build()

            Log.i(
                "GeofenceManager",
                "Syncing offline session ${session.sessionId} (${session.points.size} points)"
            )

            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i(
                            "GeofenceManager",
                            "Offline session synced ${session.sessionId}"
                        )

                        val deleted =
                            offlineGpsStorage.deleteSession(session.sessionId)

                        Log.i(
                            "GeofenceManager",
                            "Cleaned up offline track file ${session.sessionId}: $deleted"
                        )
                    } else {
                        Log.w(
                            "GeofenceManager",
                            "Offline sync HTTP ${response.code}"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e(
                    "GeofenceManager",
                    "Offline sync failed: ${e.message}"
                )
            }
        }
    }


    private fun sendTelemetry(loc: Location) {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        val json = JSONObject().apply {
            put("type", "telemetry")  // ✅ ADD THIS - Tag for routing
            put("deviceid", getTrackerDeviceId())
            put("devicename", prefs.getString("device_name", "Unknown"))
            put("projectnumber", prefs.getString("project_number", "Unknown"))
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("speed", if (loc.hasSpeed()) loc.speed else 0f)
            put("bearing", if (loc.hasBearing()) loc.bearing else 0f)
            put("altitude", if (loc.hasAltitude()) loc.altitude else 0.0)
            put("accuracy", loc.accuracy)
            put("steps", stepsSinceLastUpdate)
            put("battery", getBatteryLevel())
            put("offline", if (isOnline()) 0 else 1)
            put("trackingstate", currentState.name)
            put("state", currentState.name)
            put("movementcontext", JSONObject().apply {  // ✅ Correct (no underscore)
                put("speed", movementContext.speed)
                put("displacement", movementContext.displacement)
                put("totaldistance", movementContext.totalDistance)
                put("totaldistance_vehicle", movementContext.totalDistanceInVehicle)
                put("stepswindow", movementContext.stepsInWindow)
                put("accelvariance", movementContext.accelVariance)
            })
            put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date()))
        }.toString()
        // 🔍 ADD DETAILED LOGGING
        Log.d("Watchmen", "📤 Sending telemetry to server...")
        Log.d("Watchmen", "URL: ${BackendEndpointManager.getHttpUrlOrNull("/telemetry") ?: "WAITING_FOR_DISCOVERY"}")
        Log.i("Watchmen", "📤 SENT | State: $currentState | Disp: ${movementContext.displacement}m")

        serviceScope.launch {
            if (!postJson(json)) {
                cache.save(json)
            }
        }

    }

    private fun sendGeofenceViolationAlert(loc: Location) {
        val json = JSONObject().apply {
            put("deviceid", getTrackerDeviceId())  // ✅ FIXED: No underscore
            put("alerttype", "geofence_violation")  // ✅ FIXED: No underscore
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy", loc.accuracy)
            put("battery", getBatteryLevel())
            put("timestamp", System.currentTimeMillis())
        }.toString()

        serviceScope.launch {
            try {
                val body = json.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/alert"))
                    .post(body)
                    .build()
                client.newCall(request).execute().close()
                Log.d("Watchmen", "✅ Geofence alert sent")
            } catch (e: Exception) {
                Log.e("Watchmen", "❌ Geofence alert error: ${e.message}")
            }
        }
    }

    private suspend fun postJson(json: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!BackendEndpointManager.isConfigured()) {
                Log.d("Watchmen", "⏳ Backend not yet discovered/configured. Queuing to offline cache.")
                return@withContext false
            }

            val jsonObj = JSONObject(json)
            val type = jsonObj.optString("type", "telemetry")

            val url = when (type) {
                "security_alert" -> BackendEndpointManager.getHttpUrl("/security-alert")
                "crash"          -> BackendEndpointManager.getHttpUrl("/crash")
                "telemetry"      -> BackendEndpointManager.getHttpUrl("/telemetry")
                else             -> BackendEndpointManager.getHttpUrl("/telemetry")
            }

            val body = json.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(url)
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    lastServerResponseTime = System.currentTimeMillis()
                    lastDataSendTime = System.currentTimeMillis()
                    true
                } else {
                    Log.e("Watchmen", "❌ Server returned HTTP ${response.code}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "❌ Post failed: ${e.message}")
            false
        }
    }



    private suspend fun syncCachedTelemetry() {
        try {
            if (isOnline() && cache.count() > 0) {
                Log.i("Watchmen", "🔁 Syncing ${cache.count()} cached packets")
                val sortedFiles = cache.list().sortedBy { it.name }.take(50)
                sortedFiles.forEach { f ->
                    cache.read(f)?.let { json ->
                        if (postJson(json)) {
                            cache.remove(f)
                        } else {
                            return@forEach
                        }
                    }
                    delay(500)
                }
            }
        } catch (e: Exception) {
            Log.e("Watchmen", "Retry error: ${e.message}")
        }
    }

    private fun scheduleRetryLoop() {
        serviceScope.launch {
            while (isActive) {
                syncCachedTelemetry()
                delay(45_000)
            }
        }
    }

    private fun scheduleStateEvaluation() {
        serviceScope.launch {
            while (isActive) {
                evaluateAndTransitionState()
                delay(3_000)
            }
        }
    }

    private fun evaluateAndTransitionState() {
        val loc = lastKnownLocation ?: return
        val timeSinceLastMove = System.currentTimeMillis() - movementContext.lastSignificantMove

        movementContext.accelVariance = try {
            val snapshot = synchronized(accelLock) {
                if (accelWindow.size > 5) {
                    accelWindow.toList()
                } else {
                    emptyList()
                }
            }

            if (snapshot.isNotEmpty()) {
                val mean = snapshot.average().toFloat()
                snapshot.map { (it - mean).pow(2) }.average().toFloat()
            } else {
                0f
            }
        } catch (e: Exception) {
            Log.w("Watchmen", "⚠️ Accel variance calc error: ${e.message}")
            0f
        }

        val isVehicleCandidate = vehicleDetector.isInVehicle(
            movementContext.speed, movementContext.accelVariance,
            stepsSinceLastUpdate, movementContext.displacement
        )

        val hasRealMovement = (
                (movementContext.displacement > 5.0f && movementContext.speed > 0.5f) ||
                        movementContext.stepsInWindow > 3 ||
                        (movementContext.speed > 1.0f && movementContext.displacement > 2.0f)
                )

        val isDefinitelyStill = (
                movementContext.speed < 0.5f &&
                        movementContext.stepsInWindow == 0 &&
                        movementContext.displacement < 2.0f
                )

        val newState = when (currentState) {
            TrackingState.INITIALIZING -> {
                if (lastSentLocation != null) {
                    movementContext.lastSignificantMove = System.currentTimeMillis()
                    if (isVehicleCandidate) TrackingState.IN_VEHICLE else TrackingState.MOVING
                } else TrackingState.INITIALIZING
            }
            TrackingState.MOVING -> {
                when {
                    isVehicleCandidate -> {
                        vehicleDetector.reset()
                        kalman.setVehicleMode(true)
                        TrackingState.IN_VEHICLE
                    }
                    hasRealMovement -> {
                        movementContext.consecutiveMoveReadings++
                        movementContext.consecutiveStillReadings = 0
                        movementContext.lastSignificantMove = System.currentTimeMillis()
                        TrackingState.MOVING
                    }
                    isDefinitelyStill -> {
                        movementContext.consecutiveStillReadings++
                        movementContext.consecutiveMoveReadings = 0
                        if (movementContext.consecutiveStillReadings >= 5) TrackingState.IDLE_SUSPECTED else TrackingState.MOVING
                    }
                    else -> TrackingState.MOVING
                }
            }
            TrackingState.IN_VEHICLE -> {
                when {
                    movementContext.speed < 1.0f && stepsSinceLastUpdate > 5 -> {
                        kalman.setVehicleMode(false)
                        vehicleDetector.reset()
                        TrackingState.MOVING
                    }
                    movementContext.speed < 0.5f && movementContext.displacement < 5f -> {
                        kalman.setVehicleMode(false)
                        TrackingState.IDLE_SUSPECTED
                    }
                    else -> TrackingState.IN_VEHICLE
                }
            }
            TrackingState.IDLE_SUSPECTED -> {
                when {
                    isVehicleCandidate -> {
                        kalman.setVehicleMode(true)
                        TrackingState.IN_VEHICLE
                    }
                    hasRealMovement -> {
                        movementContext.consecutiveMoveReadings++
                        movementContext.consecutiveStillReadings = 0
                        if (movementContext.consecutiveMoveReadings >= 3) {
                            movementContext.lastSignificantMove = System.currentTimeMillis()
                            TrackingState.MOVING
                        } else TrackingState.IDLE_SUSPECTED
                    }
                    isDefinitelyStill -> {
                        movementContext.consecutiveStillReadings++
                        movementContext.consecutiveMoveReadings = 0
                        if (movementContext.consecutiveStillReadings >= idleConfirmCount) TrackingState.IDLE_CONFIRMED else TrackingState.IDLE_SUSPECTED
                    }
                    else -> TrackingState.IDLE_SUSPECTED
                }
            }
            TrackingState.IDLE_CONFIRMED -> {
                when {
                    isVehicleCandidate -> {
                        kalman.setVehicleMode(true)
                        TrackingState.IN_VEHICLE
                    }
                    hasRealMovement -> {
                        movementContext.consecutiveMoveReadings++
                        if (movementContext.consecutiveMoveReadings >= 4) {
                            movementContext.lastSignificantMove = System.currentTimeMillis()
                            TrackingState.MOVING
                        } else TrackingState.IDLE_CONFIRMED
                    }
                    timeSinceLastMove > 300_000L -> TrackingState.STOPPED
                    else -> TrackingState.IDLE_CONFIRMED
                }
            }
            TrackingState.STOPPED -> {
                when {
                    isVehicleCandidate -> {
                        kalman.setVehicleMode(true)
                        TrackingState.IN_VEHICLE
                    }
                    hasRealMovement -> {
                        movementContext.consecutiveMoveReadings++
                        if (movementContext.consecutiveMoveReadings >= 5) {
                            movementContext.lastSignificantMove = System.currentTimeMillis()
                            TrackingState.IDLE_SUSPECTED
                        } else TrackingState.STOPPED
                    }
                    else -> TrackingState.STOPPED
                }
            }
        }

        if (newState != currentState) {
            Log.i("Watchmen", "🔄 $currentState → $newState | Speed: ${movementContext.speed}m/s, Disp: ${movementContext.displacement}m")

            currentState = newState

            // 🔥 NEW: Notify dashboard immediately
            sendStateBroadcast()

            updateNotification()
            adjustUpdateInterval()
        }

    }

    private fun adjustUpdateInterval() {
        currentUpdateInterval = when (currentState) {
            TrackingState.INITIALIZING -> 8_000L
            TrackingState.MOVING -> intervalMoving
            TrackingState.IDLE_SUSPECTED -> intervalIdleSuspected
            TrackingState.IDLE_CONFIRMED -> intervalIdleConfirmed
            TrackingState.STOPPED -> intervalStopped
            TrackingState.IN_VEHICLE -> intervalVehicle
        }
    }

    private fun scheduleGeofenceSync() {
        serviceScope.launch {
            while (isActive) {
                try {
                    if (geofenceManager.shouldSync()) {
                        geofenceManager.syncFromServer(getTrackerDeviceId(), client)
                    }
                } catch (e: Exception) {
                    Log.e("Watchmen", "Geofence sync error: ${e.message}")
                }
                delay(300_000)
            }
        }
    }

    private fun registerStepSensor() {
        val stepSensor = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepSensor != null) {
            sensorManager.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("Watchmen", "👟 Step sensor registered")
        }
    }

    private fun registerAccelerometerSensor() {
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor != null) {
            sensorManager.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i("Watchmen", "📊 Accelerometer registered")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_STEP_DETECTOR -> {
                stepsSinceLastUpdate++
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val magnitude = sqrt(event.values[0].pow(2) + event.values[1].pow(2) + event.values[2].pow(2))
                synchronized(accelLock) {
                    accelWindow.addLast(magnitude)
                    if (accelWindow.size > 20) accelWindow.removeFirst()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun createNotification(): Notification {
        val channelId = "watchmen_tracker"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Watchmen Tracking",
                NotificationManager.IMPORTANCE_LOW
            )
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("Watchmen Tracker")
            .setContentText("State: $currentState")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(1, notification)
    }

    private fun getTrackerDeviceId(): String {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)

        // ✅ Return cached ID if it already exists
        val cachedId = prefs.getString("device_id", null)
        if (!cachedId.isNullOrBlank()) {
            return cachedId
        }

        // Otherwise generate ONCE
        val deviceName = prefs.getString("device_name", "DEVICE_UNKNOWN")!!
        val projectNumber = prefs.getString("project_number", "PROJECT_UNKNOWN")!!

        @SuppressLint("HardwareIds")
        val androidId = android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
        ) ?: UUID.randomUUID().toString()

        val newId = "${projectNumber}_${deviceName}_${androidId.take(8)}"

        prefs.edit().putString("device_id", newId).apply()

        return newId
    }



    private fun getBatteryLevel(): Float {
        val batteryManager = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        return batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).toFloat()
    }

    @SuppressLint("MissingPermission")
    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}