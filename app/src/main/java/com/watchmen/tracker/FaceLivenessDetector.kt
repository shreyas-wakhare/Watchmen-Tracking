package com.watchmen.tracker

import android.content.Context
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.*
import kotlinx.coroutines.tasks.await
import kotlin.math.abs
import kotlin.math.max

class FaceLivenessDetector(
    private val context: Context,
    private val stringProvider: StringProvider
) {
    var lastFaceBoundingBox: android.graphics.Rect? = null

    var lowLightMode = false
    var easyMode = false

    private fun hasHardFailure(reasons: List<LivenessReason>): Boolean {
        return reasons.any { it == LivenessReason.BLINK_TOO_LONG }
    }

    private val faceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.20f)
            .enableTracking()
            .build()
    )

    private data class BlinkEvent(val t: Long, val closed: Boolean)
    private data class MotionEvent(val t: Long, val d: Float)
    private data class PoseEvent(val t: Long, val yaw: Float, val roll: Float)

    data class LivenessResult(
        val isLive: Boolean,
        val confidence: Float,
        val reasons: List<LivenessReason>,
        val spoofType: String?
    )

    private val blinkHistory = ArrayDeque<BlinkEvent>(8)
    private val motionHistory = ArrayDeque<MotionEvent>(16)
    private val poseHistory = ArrayDeque<PoseEvent>(16)

    private var lastCx: Float? = null
    private var lastCy: Float? = null
    private var lastSize: Float? = null
    private var lastTrackingId: Int? = null

    private var blinkGateMs = 0L

    fun markBlinkInstructionNow() {
        blinkGateMs = System.currentTimeMillis()
        blinkHistory.clear()
    }

    suspend fun analyzeLiveness(image: InputImage): LivenessResult {
        return try {
            val faces = faceDetector.process(image).await()
            if (faces.isEmpty()) {
                lastFaceBoundingBox = null
                reset()
                return LivenessResult(false, 0f, listOf(LivenessReason.NO_FACE_ERROR), null)
            }

            val face = faces.first()
            lastFaceBoundingBox = face.boundingBox

            if (face.trackingId != lastTrackingId) {
                reset()
                lastTrackingId = face.trackingId
            }

            val reasons = mutableListOf<LivenessReason>()
            var score = 0f
            val lightFactor = if (lowLightMode) 0.7f else 1f

            val blink = detectBlink(face)
            if (blink.first) score += 0.30f * lightFactor else reasons.addOnce(blink.second)

            val head = detectHeadTurn(face)
            if (head.first) score += 0.30f else reasons.addOnce(head.second)

            val motion = detectMotion(face)
            if (motion.first) score += 0.25f else reasons.addOnce(motion.second)

            val quality = checkQuality(face)
            if (quality.first) score += 0.15f else reasons.addOnce(quality.second)

            val confidence = score.coerceIn(0f, 1f)
            val threshold = if (easyMode) 0.55f else 0.65f

            val hardFail = hasHardFailure(reasons)
            val isLive = !hardFail && confidence >= threshold

            val spoofType = when {
                hardFail && reasons.contains(LivenessReason.HEAD_TURN) -> "photo_static"
                hardFail && reasons.contains(LivenessReason.BLINK_TOO_LONG) -> "video_loop"
                hardFail && reasons.contains(LivenessReason.MOTION_REQUIRED) -> "photo"
                !isLive -> "unknown"
                else -> null
            }

            LivenessResult(
                isLive,
                confidence,
                if (isLive) listOf(LivenessReason.VERIFIED) else reasons.take(2),
                spoofType
            )
        } catch (_: Exception) {
            LivenessResult(false, 0f, listOf(LivenessReason.ANALYSIS_ERROR), "error")
        }
    }

    private fun detectBlink(face: Face): Pair<Boolean, LivenessReason> {
        val now = System.currentTimeMillis()
        val gate = if (blinkGateMs > 0) blinkGateMs else now - 5000

        val l = face.leftEyeOpenProbability ?: return false to LivenessReason.BLINK_REQUIRED
        val r = face.rightEyeOpenProbability ?: return false to LivenessReason.BLINK_REQUIRED

        val closed = l < 0.22f && r < 0.22f
        blinkHistory.addLast(BlinkEvent(now, closed))
        if (blinkHistory.size > 8) blinkHistory.removeFirst()

        val window = blinkHistory.filter { it.t >= max(gate, now - 4000) }
        val closedFrames = window.count { it.closed }
        val transitions = window.zipWithNext().count { !it.first.closed && it.second.closed }

        return when {
            transitions >= 1 && closedFrames in 2..6 -> true to LivenessReason.EYES_OPEN
            transitions >= 1 -> false to LivenessReason.BLINK_TOO_LONG
            else -> false to LivenessReason.BLINK_REQUIRED
        }
    }

    private fun detectHeadTurn(face: Face): Pair<Boolean, LivenessReason> {
        val now = System.currentTimeMillis()
        poseHistory.addLast(PoseEvent(now, face.headEulerAngleY, face.headEulerAngleZ))
        if (poseHistory.size > 16) poseHistory.removeFirst()

        val recent = poseHistory.filter { it.t >= now - 2500 }
        if (recent.size < 4) return false to LivenessReason.HEAD_PASSIVE

        val yawRange = recent.maxOf { it.yaw } - recent.minOf { it.yaw }
        val rollRange = recent.maxOf { it.roll } - recent.minOf { it.roll }

        return if (yawRange >= 12f || rollRange >= 8f)
            true to LivenessReason.HEAD_OK
        else
            false to LivenessReason.HEAD_TURN
    }

    private fun detectMotion(face: Face): Pair<Boolean, LivenessReason> {
        val bb = face.boundingBox
        val cx = bb.exactCenterX()
        val cy = bb.exactCenterY()
        val size = bb.width().coerceAtLeast(1).toFloat()

        if (lastCx != null) {
            val d = (abs(cx - lastCx!!) + abs(cy - lastCy!!)) / size
            motionHistory.addLast(MotionEvent(System.currentTimeMillis(), d))
            if (motionHistory.size > 16) motionHistory.removeFirst()
        }

        lastCx = cx
        lastCy = cy
        lastSize = size

        val last5 = motionHistory.takeLast(5)
        if (last5.size < 5) return false to LivenessReason.BUILDING_MOTION

        val avg = last5.map { it.d }.average()
        return if (avg >= 0.01) true to LivenessReason.MOTION_OK
        else false to LivenessReason.MOTION_REQUIRED
    }

    private fun checkQuality(face: Face): Pair<Boolean, LivenessReason> {
        val ratio = face.boundingBox.width() /
                context.resources.displayMetrics.widthPixels.toFloat()
        return if (ratio > 0.15f) true to LivenessReason.TRACKING_OK
        else false to LivenessReason.TRACKING_WEAK
    }

    private fun MutableList<LivenessReason>.addOnce(r: LivenessReason) {
        if (!contains(r)) add(r)
    }

    fun reset() {
        blinkHistory.clear()
        motionHistory.clear()
        poseHistory.clear()
        lastCx = null
        lastCy = null
        lastSize = null
        lastTrackingId = null
    }
}
