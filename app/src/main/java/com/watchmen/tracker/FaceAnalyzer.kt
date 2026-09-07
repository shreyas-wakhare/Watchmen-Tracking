package com.watchmen.tracker

import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.*

class FaceAnalyzer(
    private val previewView: PreviewView,
    private val overlay: FaceOverlayView,
    private val livenessDetector: FaceLivenessDetector,
    private val scope: CoroutineScope,
    private val uiCallback: (FaceLivenessDetector.LivenessResult) -> Unit
) : ImageAnalysis.Analyzer {

    private val transformFactory = ImageProxyTransformFactory().apply {
        isUsingCropRect = true
    }

    override fun analyze(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: run {
            imageProxy.close()
            return
        }

        // Let CameraX handle rotation; give ML Kit the same raw buffer orientation
        val inputImage = InputImage.fromMediaImage(
            mediaImage,
            0
        )


        scope.launch(Dispatchers.Default) {
            val result = livenessDetector.analyzeLiveness(inputImage)
            val box = livenessDetector.lastFaceBoundingBox

            // Always inform UI about liveness
            withContext(Dispatchers.Main) {
                uiCallback(result)
            }

            if (box == null) {
                withContext(Dispatchers.Main) {
                    overlay.clear()
                    imageProxy.close()
                }
                return@launch
            }

            val rect = RectF(box)
            val source = transformFactory.getOutputTransform(imageProxy)

            withContext(Dispatchers.Main) {
                try {
                    val target = previewView.outputTransform
                    if (target != null) {
                        CoordinateTransform(source, target).mapRect(rect)

                        Log.d(
                            "FaceOverlay",
                            "box=$box view=${previewView.width}x${previewView.height} mapped=$rect"
                        )

                        overlay.updateFromViewCoordinates(
                            rectInView = rect,
                            confidence = result.confidence,
                            state = if (result.isLive) {
                                FaceOverlayView.CaptureState.LIVE_CONFIRMED
                            } else {
                                FaceOverlayView.CaptureState.VERIFYING
                            }
                        )
                    } else {
                        overlay.clear()
                    }
                } finally {
                    imageProxy.close()
                }
            }
        }
    }
}
