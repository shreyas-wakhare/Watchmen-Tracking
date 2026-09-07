package com.watchmen.tracker

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.abs

class FaceOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val COLOR_SUCCESS = ContextCompat.getColor(context, android.R.color.holo_green_light)
    private val COLOR_PENDING = Color.WHITE
    private val COLOR_FAILURE = ContextCompat.getColor(context, android.R.color.holo_red_light)
    private val COLOR_OVERRIDE = ContextCompat.getColor(context, android.R.color.holo_orange_light)

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#8000FF00")
    }

    private val smoothRect = RectF()
    private var faceRect: RectF? = null
    private val SMOOTHING_FACTOR = 0.25f

    private var confidence = 0f
    private var state = CaptureState.VERIFYING

    enum class CaptureState {
        VERIFYING,
        LIVE_CONFIRMED,
        OVERRIDE_AVAILABLE,
        FORCE_CAPTURE
    }
    // In FaceOverlayView
    private var isLocked = false

    fun lockCurrentRect() {
        if (faceRect != null) {
            isLocked = true
        }
    }

    fun unlock() {
        isLocked = false
    }

    /**
     * Rect MUST already be in PreviewView coordinates.
     */
    fun updateFromViewCoordinates(
        rectInView: RectF,
        confidence: Float,
        state: CaptureState
    ) {
        if (width == 0 || height == 0) return

        // If locked, just update state/color and redraw
        if (isLocked) {
            this.confidence = confidence
            this.state = state
            postInvalidateOnAnimation()
            return
        }

        this.confidence = confidence
        this.state = state

        if (smoothRect.isEmpty) {
            smoothRect.set(rectInView)
        } else {
            smoothRect.left   += (rectInView.left   - smoothRect.left)   * SMOOTHING_FACTOR
            smoothRect.top    += (rectInView.top    - smoothRect.top)    * SMOOTHING_FACTOR
            smoothRect.right  += (rectInView.right  - smoothRect.right)  * SMOOTHING_FACTOR
            smoothRect.bottom += (rectInView.bottom - smoothRect.bottom) * SMOOTHING_FACTOR
        }

        faceRect = smoothRect
        postInvalidateOnAnimation()
    }


    fun clear() {
        faceRect = null
        smoothRect.setEmpty()
        isLocked = false
        invalidate()
    }


    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val rect = faceRect ?: return
        if (rect.width() <= 0f || rect.height() <= 0f) return

        boxPaint.color = when (state) {
            CaptureState.LIVE_CONFIRMED -> COLOR_SUCCESS
            CaptureState.OVERRIDE_AVAILABLE,
            CaptureState.FORCE_CAPTURE -> COLOR_OVERRIDE
            CaptureState.VERIFYING -> {
                if (confidence < 0.5f) {
                    lerpColor(COLOR_FAILURE, COLOR_PENDING, confidence * 2f)
                } else {
                    lerpColor(COLOR_PENDING, COLOR_SUCCESS, (confidence - 0.5f) * 2f)
                }
            }
        }

        canvas.drawRoundRect(rect, 28f, 28f, boxPaint)

        if (state == CaptureState.VERIFYING) {
            val now = System.currentTimeMillis()
            val progress = (now % 1500L) / 1500f
            val y = rect.top + rect.height() * progress
            scanPaint.alpha = (255 * (1f - abs(progress - 0.5f) * 2f)).toInt()

            canvas.save()
            canvas.clipRect(rect)
            canvas.drawLine(rect.left, y, rect.right, y, scanPaint)
            canvas.restore()
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val c = t.coerceIn(0f, 1f)
        return Color.argb(
            (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * c).toInt(),
            (Color.red(a)   + (Color.red(b)   - Color.red(a))   * c).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * c).toInt(),
            (Color.blue(a)  + (Color.blue(b)  - Color.blue(a))  * c).toInt()
        )
    }
}
