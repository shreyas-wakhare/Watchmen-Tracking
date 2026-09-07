package com.watchmen.tracker

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.Calendar
import java.util.concurrent.Executors
import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream

class CameraCaptureActivity : AppCompatActivity() {

    private lateinit var faceOverlay: FaceOverlayView
    private lateinit var previewView: PreviewView
    private lateinit var captureButton: Button
    private lateinit var hintText: TextView
    private lateinit var confidenceBar: ProgressBar
    private lateinit var trialText: TextView
    private lateinit var instructionIcon: ImageView

    private lateinit var livenessDetector: FaceLivenessDetector
    private lateinit var ttsHelper: MultilingualTTS
    private lateinit var stringProvider: StringProvider
    private lateinit var trialManager: TrialManager
    private lateinit var currentLanguage: String

    private var imageCapture: ImageCapture? = null
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null

    private enum class CaptureState { VERIFYING, LIVE_CONFIRMED, OVERRIDE_AVAILABLE, FORCE_CAPTURE }
    @Volatile private var captureState = CaptureState.VERIFYING

    @Volatile private var isAnalyzing = false
    private var lastAnalysisTime = 0L
    private val ANALYSIS_INTERVAL_MS = 500L
    private val LIVENESS_TIMEOUT_MS = 20_000L

    private var lastPromptMs = 0L
    private val REPROMPT_MS = 4000L

    private var trialStartTimeMs = 0L
    private var smoothedConfidence = 0f

    private var lastLivenessConfidence = 0f
    private var lastLivenessIsLive = false
    private var lastSpoofType: String? = null
    private var lastLivenessReasons: List<LivenessReason> = emptyList()

    private val REQUIRED_FAIL_FRAMES = 60
    private var consecutiveFailureFrames = 0
    private var lastReportedFailureTimeMs = 0L
    private val MIN_REPORT_INTERVAL_MS = 20000L

    private var isHourlyCheckin = false
    private var hourlyAutoCaptureTriggered = false  // Prevent multiple captures

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera_capture)

        // Check if this is an hourly check-in
        isHourlyCheckin = intent.getBooleanExtra("HOURLY_CHECKIN", false)

        // Initialize all views
        previewView = findViewById(R.id.previewView)
        faceOverlay = findViewById(R.id.faceOverlay)
        captureButton = findViewById(R.id.btnCapture)
        hintText = findViewById(R.id.tvLivenessHint)
        confidenceBar = findViewById(R.id.confidenceBar)
        trialText = findViewById(R.id.tvTrialCount)
        instructionIcon = findViewById(R.id.ivInstruction)

        currentLanguage = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
            .getString("app_language", "en") ?: "en"

        ttsHelper = MultilingualTTS(this)
        ttsHelper.initialize({}, {})

        stringProvider = AndroidResourceStringProvider(this)
        trialManager = TrialManager(this, stringProvider)

        smoothedConfidence = 0f

        livenessDetector = FaceLivenessDetector(this, stringProvider).apply {
            lowLightMode = isNightTime()
            easyMode = true
        }

        // Setup based on mode
        if (isHourlyCheckin) {
            setupQuickCaptureMode()
        } else {
            setState(CaptureState.VERIFYING)
            startCamera()
            speakBlinkInstruction()
        }

        captureButton.setOnClickListener {
            when (captureState) {
                CaptureState.LIVE_CONFIRMED,
                CaptureState.FORCE_CAPTURE -> takePhoto(overrideUsed = false)
                CaptureState.OVERRIDE_AVAILABLE -> showSupervisorOverrideDialog()
                else -> { /* ignore */ }
            }
        }
    }

    // ---------- UI + LIVENESS ----------
    private fun resizeBitmap(src: Bitmap, maxWidth: Int = 1280): Bitmap {
        if (src.width <= maxWidth) return src

        val ratio = maxWidth.toFloat() / src.width
        val newHeight = (src.height * ratio).toInt()

        return Bitmap.createScaledBitmap(src, maxWidth, newHeight, true)
    }
    private fun compressToJpeg(bitmap: Bitmap): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(
            Bitmap.CompressFormat.JPEG,
            60,   // ✅ 60–65 sweet spot
            stream
        )
        return stream.toByteArray()
    }

    private fun setupQuickCaptureMode() {
        setState(CaptureState.VERIFYING)
        startCamera()

        updateHint(stringProvider.getString("hourly_checkin_mode"))
        captureButton.text = stringProvider.getString("button_auto_capturing")
        captureButton.isEnabled = false

        // Disable trial system for hourly check-ins
        trialText.text = stringProvider.getString("hourly_checkin_label")
    }

    private fun setState(state: CaptureState) {
        captureState = state
        runOnUiThread {
            // Update Trial UI text only if not hourly check-in
            if (!isHourlyCheckin) {
                trialText.text = stringProvider.getString(
                    "liveness_trial_prompt",
                    trialManager.currentTrial,
                    trialManager.maxTrials
                )
            }

            when (state) {
                CaptureState.VERIFYING -> {
                    trialStartTimeMs = System.currentTimeMillis()
                    captureButton.isEnabled = false
                    captureButton.text = stringProvider.getString("button_verifying")
                    updateHint(pickHint(lastLivenessReasons))
                }
                CaptureState.LIVE_CONFIRMED -> {
                    captureButton.isEnabled = true
                    captureButton.text = stringProvider.getString("button_capture")
                    updateHint(stringProvider.getString("liveness_verified"))
                }
                CaptureState.OVERRIDE_AVAILABLE -> {
                    captureButton.isEnabled = true
                    captureButton.text = stringProvider.getString("button_override")
                    updateHint(stringProvider.getString("hint_verification_failed"))
                }
                CaptureState.FORCE_CAPTURE -> {
                    captureButton.isEnabled = true
                    captureButton.text = stringProvider.getString("button_capture_anyway")
                    updateHint(stringProvider.getString("hint_force_capture"))
                }
            }
        }
    }

    private fun triggerHapticFeedback(type: Int) {
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) {
            val effectId = when (type) {
                1 -> VibrationEffect.EFFECT_CLICK
                2 -> VibrationEffect.EFFECT_TICK
                else -> VibrationEffect.EFFECT_HEAVY_CLICK
            }
            vibrator.vibrate(VibrationEffect.createPredefined(effectId))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(50)
        }
    }

    private fun pickHint(reasons: List<LivenessReason>): String {
        val criticalReason = reasons.firstOrNull {
            it == LivenessReason.NO_FACE_ERROR ||
                    it == LivenessReason.ANALYSIS_ERROR ||
                    it == LivenessReason.TOO_SMALL
        }

        return when (criticalReason ?: reasons.firstOrNull()) {
            LivenessReason.NO_FACE_ERROR, LivenessReason.NO_FACE ->
                stringProvider.getString("liveness_no_face")
            LivenessReason.TOO_SMALL ->
                stringProvider.getString("liveness_move_closer")
            LivenessReason.HEAD_TURN ->
                stringProvider.getString("liveness_turn_head")
            LivenessReason.MOTION_REQUIRED ->
                stringProvider.getString("liveness_motion_required")
            LivenessReason.BLINK_TOO_LONG ->
                stringProvider.getString("liveness_blink_too_long")
            LivenessReason.BLINK_REQUIRED ->
                stringProvider.getString("liveness_blink")
            LivenessReason.EYES_CLOSED ->
                stringProvider.getString("liveness_eyes_closed")
            LivenessReason.LOW_LIGHT ->
                stringProvider.getString("liveness_low_light")
            LivenessReason.HOLD_STEADY ->
                stringProvider.getString("liveness_hold_steady")
            LivenessReason.ANALYSIS_ERROR ->
                stringProvider.getString("liveness_analysis_error")
            else ->
                stringProvider.getString("liveness_hold_steady")
        }
    }

    private fun updateHint(text: String) {
        runOnUiThread { hintText.text = text }
    }

    private fun updateConfidenceBar(confidence: Float) {
        val ALPHA = 0.3f
        smoothedConfidence = (1 - ALPHA) * smoothedConfidence + ALPHA * confidence

        runOnUiThread {
            confidenceBar.progress = (smoothedConfidence * 100).toInt()
        }
    }

    private fun speakBlinkInstruction() {
        livenessDetector.markBlinkInstructionNow()
        ttsHelper.speak(
            SpeechTable.get("blink_instruction", currentLanguage),
            currentLanguage,
            false
        )
    }

    private fun maybeReprompt(result: FaceLivenessDetector.LivenessResult) {
        if (isHourlyCheckin) return  // Skip reprompts for hourly

        val now = System.currentTimeMillis()
        if (now - lastPromptMs < REPROMPT_MS) return

        when {
            result.reasons.contains(LivenessReason.BLINK_REQUIRED)
                    && !result.reasons.contains(LivenessReason.BLINK_TOO_LONG) -> {
                lastPromptMs = now
                livenessDetector.markBlinkInstructionNow()
                ttsHelper.speak(
                    SpeechTable.get("blink_instruction", currentLanguage),
                    currentLanguage,
                    false
                )
                triggerHapticFeedback(2)
            }
            result.reasons.contains(LivenessReason.HEAD_TURN) -> {
                lastPromptMs = now
                ttsHelper.speak(
                    SpeechTable.get("turn_head", currentLanguage),
                    currentLanguage,
                    false
                )
                triggerHapticFeedback(2)
            }
        }
    }

    private fun animateInstructionIcon(reasons: List<LivenessReason>) {
        val iconRes = when {
            reasons.contains(LivenessReason.BLINK_REQUIRED) -> R.drawable.ic_eye_open
            reasons.contains(LivenessReason.HEAD_TURN) -> R.drawable.ic_arrow_left_right
            else -> null
        }

        runOnUiThread {
            if (iconRes != null) {
                instructionIcon.setImageResource(iconRes)
                if (instructionIcon.alpha == 0f) {
                    instructionIcon.animate()
                        .alpha(1f)
                        .scaleX(1.2f).scaleY(1.2f)
                        .setDuration(200)
                        .start()
                } else {
                    instructionIcon.animate()
                        .scaleX(1.1f).scaleY(1.1f)
                        .setDuration(100)
                        .withEndAction {
                            instructionIcon.animate()
                                .scaleX(1.0f).scaleY(1.0f)
                                .setDuration(100)
                                .start()
                        }
                        .start()
                }
            } else {
                if (instructionIcon.alpha > 0f) {
                    instructionIcon.animate()
                        .alpha(0f)
                        .scaleX(0.8f).scaleY(0.8f)
                        .setDuration(300)
                        .start()
                }
            }
        }
    }

    // ---------- CAMERA + ANALYZER ----------

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)

        providerFuture.addListener({
            val provider = providerFuture.get()

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(previewView.display.rotation)
                .build()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(previewView.display.rotation)
                .build()

            val analyzer = FaceAnalyzer(
                previewView = previewView,
                overlay = faceOverlay,
                livenessDetector = livenessDetector,
                scope = lifecycleScope
            ) { result ->

                lastLivenessConfidence = result.confidence
                lastLivenessIsLive = result.isLive
                lastSpoofType = result.spoofType
                lastLivenessReasons = result.reasons

                updateConfidenceBar(result.confidence)
                animateInstructionIcon(result.reasons)
                maybeReprompt(result)

                if (result.isLive) {
                    if (captureState != CaptureState.LIVE_CONFIRMED) {
                        triggerHapticFeedback(1)
                        setState(CaptureState.LIVE_CONFIRMED)

                        // ✅ Freeze box when verified
                        faceOverlay.lockCurrentRect()

                        if (isHourlyCheckin && !hourlyAutoCaptureTriggered) {
                            hourlyAutoCaptureTriggered = true
                            takePhoto(overrideUsed = false)
                        }
                    }
                } else {
                    // Optional: allow box to move again when we fully reset
                    handleLivenessFailureThrottled(result.confidence)
                }
            }


            imageAnalysis.setAnalyzer(
                ContextCompat.getMainExecutor(this),
                analyzer
            )

            provider.unbindAll()

            camera = provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_FRONT_CAMERA,
                preview,
                imageAnalysis,
                imageCapture
            )

            updateLowLightModeFromCamera()
            maybeEnableTorchAtNight()

        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateLowLightModeFromCamera() {
        val state = camera?.cameraInfo?.exposureState ?: return
        val index = state.exposureCompensationIndex
        val range = state.exposureCompensationRange
        val max = range.upper.coerceAtLeast(1)
        val isLow = index >= (max - 1)
        livenessDetector.lowLightMode = isLow
        Log.d("Liveness", "Exposure Index: $index/$max. Low Light Mode: $isLow")
    }

    private fun handleLivenessFailureThrottled(confidence: Float) {
        val now = System.currentTimeMillis()
        consecutiveFailureFrames++

        if (consecutiveFailureFrames >= REQUIRED_FAIL_FRAMES) {
            if (now - lastReportedFailureTimeMs >= MIN_REPORT_INTERVAL_MS || lastReportedFailureTimeMs == 0L) {
                lastReportedFailureTimeMs = now
                handleLivenessFailure()
            } else {
                Log.d("Liveness", "Failure throttled. Consecutive frames: $consecutiveFailureFrames")
            }
        } else {
            Log.d("Liveness", "Failure count: $consecutiveFailureFrames/$REQUIRED_FAIL_FRAMES")
        }
    }

    private fun handleLivenessFailure() {
        if (trialManager.hasRemainingTrials) {
            consecutiveFailureFrames = 0
            trialManager.registerFailedTrial()
            livenessDetector.reset()
            faceOverlay.unlock()          // ✅ allow box to move again
            faceOverlay.clear()

            setState(CaptureState.VERIFYING)
            speakBlinkInstruction()

            lifecycleScope.launch {
                val stringReasons =
                    lastLivenessReasons.map { stringProvider.getString(it.stringKey) }
                trialManager.reportFailureToSupervisor(
                    stringReasons,
                    lastLivenessConfidence
                )

                val attemptNumber = trialManager.currentTrial - 1
                val maxAttempts = trialManager.maxTrials

                val failureMessage = stringProvider.getString(
                    "liveness_failed_trial_report",
                    attemptNumber,
                    maxAttempts
                )
                ttsHelper.speak(failureMessage, currentLanguage, false)

                if (trialManager.isFinalTrial()) {
                    updateHint(stringProvider.getString("liveness_final_try_prompt"))
                    setState(CaptureState.VERIFYING)
                    speakBlinkInstruction()
                } else {
                    updateHint(
                        stringProvider.getString(
                            "liveness_trial_prompt",
                            trialManager.currentTrial,
                            maxAttempts
                        )
                    )
                    setState(CaptureState.VERIFYING)
                    speakBlinkInstruction()
                }
            }
        } else if (trialManager.isFinalTrial()) {
            consecutiveFailureFrames = 0
            setState(CaptureState.FORCE_CAPTURE)
            ttsHelper.speak(
                stringProvider.getString("force_capture_enabled"),
                currentLanguage,
                false
            )
        }
    }

    // ---------- CAPTURE + UPLOAD ----------

    private fun takePhoto(overrideUsed: Boolean) {
        val capture = imageCapture ?: return

        if (!overrideUsed &&
            captureState != CaptureState.LIVE_CONFIRMED &&
            captureState != CaptureState.FORCE_CAPTURE
        ) {
            return
        }

        val file = File(cacheDir, "photo_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        try {
                            val optimized = createOptimizedImage(file)

// delete original
                            file.delete()

// ✅ CHECK OPTIMIZED FILE
                            if (optimized.length() > 250_000) {
                                Log.w("Camera", "Image still large (${optimized.length()} bytes)")
                            }

                            Log.i(
                                "Camera",
                                "Uploading image size = ${optimized.length() / 1024} KB"
                            )

                            uploadImageFile(optimized, overrideUsed)



                        } catch (e: Exception) {
                            Log.e("Camera", "Image optimization failed: ${e.message}")
                        } finally {
                            finish()
                        }
                    }
                }


                override fun onError(exc: ImageCaptureException) {
                    Log.e("Camera", "Save failed: ${exc.message}")
                }
            }
        )
    }
    private fun createOptimizedImage(original: File): File {
        val bitmap = BitmapFactory.decodeFile(original.absolutePath)

        val resized = resizeBitmap(bitmap, 1280)
        val outputFile = File(cacheDir, "opt_${original.name}")

        FileOutputStream(outputFile).use { out ->
            resized.compress(
                Bitmap.CompressFormat.JPEG,
                60, // ✅ KEY VALUE
                out
            )
        }

        bitmap.recycle()
        resized.recycle()

        return outputFile
    }

    private fun uploadImageFile(file: File, overrideUsed: Boolean) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val imageRequestBody =
                    file.asRequestBody("image/jpeg".toMediaTypeOrNull())
                val stringReasons =
                    lastLivenessReasons.map { stringProvider.getString(it.stringKey) }

                val isVerified = if (isHourlyCheckin) {
                    true
                } else {
                    lastLivenessIsLive
                }

                val confidence = if (isHourlyCheckin) {
                    0.5f
                } else {
                    lastLivenessConfidence
                }

                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("device_id", getTrackerDeviceId())
                    .addFormDataPart(
                        "liveness_verified_client",
                        isVerified.toString()
                    )
                    .addFormDataPart(
                        "liveness_confidence",
                        confidence.toString()
                    )
                    .addFormDataPart(
                        "spoof_type",
                        lastSpoofType ?: "none"
                    )
                    .addFormDataPart(
                        "override_used",
                        overrideUsed.toString()
                    )
                    .addFormDataPart(
                        "liveness_reasons",
                        stringReasons.joinToString("; ")
                    )
                    .addFormDataPart("image", file.name, imageRequestBody)
                    .build()

                val request = Request.Builder()
                    .url(BackendEndpointManager.getHttpUrl("/upload"))
                    .post(body)
                    .build()

                OkHttpClient().newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: java.io.IOException) {
                        Log.w("Upload", "Upload failed: ${e.message}")
                        file.delete()
                    }

                    override fun onResponse(call: Call, response: Response) {
                        Log.i("Upload", "✅ Upload success (${response.code})")
                        response.close()
                        file.delete()
                    }
                })

            } catch (e: Exception) {
                Log.e("Upload", "Upload exception: ${e.message}")
            }
        }
    }

    private fun showSupervisorOverrideDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Supervisor PIN"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }

        val correctPin = getSharedPreferences("watchmen_prefs", MODE_PRIVATE)
            .getString("supervisor_pin", "1234")

        AlertDialog.Builder(this)
            .setTitle("Supervisor Override")
            .setView(input)
            .setPositiveButton(stringProvider.getString("button_confirm")) { _, _ ->
                if (input.text.toString() == correctPin) {
                    takePhoto(overrideUsed = true)
                } else {
                    Log.w("Override", "Incorrect PIN")
                }
            }
            .setNegativeButton(stringProvider.getString("button_cancel"), null)
            .show()
    }

    // ---------- MISC ----------

    private fun maybeEnableTorchAtNight() {
        if (livenessDetector.lowLightMode &&
            camera?.cameraInfo?.hasFlashUnit() == true
        ) {
            camera?.cameraControl?.enableTorch(true)
        }
    }

    private fun isNightTime(): Boolean {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return hour >= 18 || hour <= 6
    }

    private fun getTrackerDeviceId(): String {
        val prefs = getSharedPreferences("watchmen_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_token", "UNKNOWN") ?: "UNKNOWN"
    }

    override fun onDestroy() {
        super.onDestroy()
        livenessDetector.reset()
        trialManager.reset()
        cameraExecutor.shutdown()
        camera?.cameraControl?.enableTorch(false)
    }
}
