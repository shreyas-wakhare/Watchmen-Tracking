package com.watchmen.tracker

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

class MovementAuthenticityDetector(
    private val sensorManager: SensorManager,
    private val listener: MovementListener? = null
) : SensorEventListener {

    interface MovementListener {
        fun onSuspiciousMovement(result: AuthenticityResult)
    }

    // ----------------------------
    // Sensor history buffers
    // ----------------------------
    private val accelHistory = ArrayDeque<Triple<Float, Float, Float>>(120)
    private val gyroHistory = ArrayDeque<Triple<Float, Float, Float>>(60)

    // ----------------------------
    // Step delta reconstruction
    // ----------------------------
    private var lastStepCount = -1
    private var lastDeltaTimeMs = 0L

    // ----------------------------
    // Alert throttling
    // ----------------------------
    private var lastAlertTimeMs = 0L
    private val ALERT_COOLDOWN_MS = 4000L

    data class AuthenticityResult(
        val isAuthentic: Boolean,
        val suspiciousActivity: String?,
        val confidence: Float
    )

    // ----------------------------
    // Lifecycle
    // ----------------------------
    fun startMonitoring() {
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun stopMonitoring() {
        sensorManager.unregisterListener(this)
        accelHistory.clear()
        gyroHistory.clear()
        lastStepCount = -1
    }

    // ----------------------------
    // Sensor callbacks
    // ----------------------------
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                synchronized(accelHistory) {
                    accelHistory.addLast(
                        Triple(event.values[0], event.values[1], event.values[2])
                    )
                    if (accelHistory.size > 120) accelHistory.removeFirst()
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                synchronized(gyroHistory) {
                    gyroHistory.addLast(
                        Triple(event.values[0], event.values[1], event.values[2])
                    )
                    if (gyroHistory.size > 60) gyroHistory.removeFirst()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ----------------------------
    // Main analysis entry point
    // ----------------------------
    fun analyzeMovement(stepsTotal: Int): AuthenticityResult {

        // --- reconstruct delta ---
        val stepDelta = if (lastStepCount < 0) {
            lastStepCount = stepsTotal
            0
        } else {
            val d = stepsTotal - lastStepCount
            lastStepCount = stepsTotal
            d.coerceAtLeast(0)
        }

        if (stepDelta > 0) {
            lastDeltaTimeMs = System.currentTimeMillis()
        }

        val accelSnapshot = synchronized(accelHistory) { accelHistory.toList() }
        val gyroSnapshot = synchronized(gyroHistory) { gyroHistory.toList() }

        if (accelSnapshot.size < 15) {
            return AuthenticityResult(true, null, 1f)
        }

        var best = AuthenticityResult(true, null, 1f)

        // ----------------------------
        // Rule 1: violent shaking + steps
        // ----------------------------
        if (stepDelta > 0 && detectPhoneShaking(accelSnapshot)) {
            best = AuthenticityResult(
                isAuthentic = false,
                suspiciousActivity = "Phone shaking to fake steps",
                confidence = 0.9f
            )
        }

        // ----------------------------
        // Rule 2: phone flat & stable but steps occur
        // ----------------------------
        if (stepDelta > 0 && detectGroundPlacement(accelSnapshot, gyroSnapshot)) {
            val r = AuthenticityResult(
                isAuthentic = false,
                suspiciousActivity = "Phone stationary on surface while steps increase",
                confidence = 0.85f
            )
            if (r.confidence > best.confidence) best = r
        }

        // ----------------------------
        // Rule 3: mechanical vibration (independent of steps)
        // ----------------------------
        if (detectUnnaturalVibration(accelSnapshot)) {
            val r = AuthenticityResult(
                isAuthentic = false,
                suspiciousActivity = "Artificial periodic vibration detected",
                confidence = 0.8f
            )
            if (r.confidence > best.confidence) best = r
        }

        maybeNotify(best)
        return best
    }

    // ----------------------------
    // Notification gating
    // ----------------------------
    private fun maybeNotify(result: AuthenticityResult) {
        if (result.isAuthentic || result.suspiciousActivity == null) return

        val now = System.currentTimeMillis()
        if (now - lastAlertTimeMs >= ALERT_COOLDOWN_MS) {
            lastAlertTimeMs = now
            Log.w(
                "MovementAuth",
                "⚠️ ${result.suspiciousActivity} (confidence=${result.confidence})"
            )
            listener?.onSuspiciousMovement(result)
        }
    }

    // ----------------------------
    // Detection primitives
    // ----------------------------
    private fun detectPhoneShaking(accelData: List<Triple<Float, Float, Float>>): Boolean {
        val magnitudes = accelData.map { (x, y, z) ->
            sqrt(x.pow(2) + y.pow(2) + z.pow(2))
        }

        var spikes = 0
        for (i in 1 until magnitudes.size) {
            if (abs(magnitudes[i] - magnitudes[i - 1]) > 4.5f) spikes++
        }

        return spikes.toFloat() / magnitudes.size > 0.22f
    }

    private fun detectGroundPlacement(
        accelData: List<Triple<Float, Float, Float>>,
        gyroData: List<Triple<Float, Float, Float>>
    ): Boolean {
        if (gyroData.size < 10) return false

        val accelVar = calculateVariance(accelData)
        val gyroVar = calculateVariance(gyroData)

        val avgZ = accelData.map { it.third }.average().toFloat()
        val flat = abs(abs(avgZ) - 9.8f) < 1.1f

        return accelVar < 0.06f && gyroVar < 0.03f && flat
    }

    private fun detectUnnaturalVibration(accelData: List<Triple<Float, Float, Float>>): Boolean {
        if (accelData.size < 25) return false

        val mags = accelData.map { (x, y, z) ->
            sqrt(x.pow(2) + y.pow(2) + z.pow(2))
        }

        val mean = mags.average().toFloat()
        val dev = mags.map { it - mean }

        var corr = 0f
        val lag = 6
        for (i in 0 until dev.size - lag) {
            corr += dev[i] * dev[i + lag]
        }

        corr /= (dev.size - lag)
        return abs(corr) > 1.3f
    }

    private fun calculateVariance(data: List<Triple<Float, Float, Float>>): Float {
        val mags = data.map { (x, y, z) ->
            sqrt(x.pow(2) + y.pow(2) + z.pow(2))
        }
        val mean = mags.average().toFloat()
        return mags.map { (it - mean).pow(2) }.average().toFloat()
    }
}
