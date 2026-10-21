package com.example.floatingassistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowManager

/**
 * ElementMarkerOverlay — Visual Target Highlight
 *
 * Draws a pulsing green rounded-rectangle border on top of the UI element
 * that the user needs to tap. The overlay is completely pass-through —
 * all touch events go straight to the underlying app.
 *
 * Usage:
 * ```
 * val marker = ElementMarkerOverlay(context)
 * marker.showMarker(Rect(100, 500, 300, 580))
 * // ... later ...
 * marker.hideMarker()
 * ```
 */
class ElementMarkerOverlay(private val context: Context) {

    companion object {
        private const val TAG = "[NavigationEngine]"

        /** Border thickness in dp */
        private const val BORDER_WIDTH_DP = 3f

        /** Corner radius for the rounded rect in dp */
        private const val CORNER_RADIUS_DP = 10f

        /** Padding added around the detected bounds in dp */
        private const val PADDING_DP = 4f

        /** Auto-hide timeout in ms */
        private const val AUTO_HIDE_MS = 8000L
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The custom view that draws the marker rectangle */
    private val markerView = MarkerView(context)

    /** Pulse animation on the marker view's alpha */
    private var pulseAnimator: ObjectAnimator? = null

    /** Auto-hide runnable */
    private val autoHideRunnable = Runnable { hideMarker() }

    @Volatile private var isAdded = false
    @Volatile private var isDestroyed = false

    private val layoutParams: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Shows the marker highlight at the given screen coordinates.
     * If a marker is already visible, it moves to the new position.
     */
    fun showMarker(bounds: Rect) {
        if (isDestroyed) return

        postToMain {
            // Apply padding
            val pad = dpToPx(PADDING_DP.toInt())
            markerView.targetBounds = RectF(
                (bounds.left - pad).toFloat(),
                (bounds.top - pad).toFloat(),
                (bounds.right + pad).toFloat(),
                (bounds.bottom + pad).toFloat()
            )
            markerView.invalidate()

            attachIfNeeded()

            // Start pulse animation
            startPulse()
            // FIX: Removed Log.d spam for Marker bounds. Do not add it back.
        }
    }

    /**
     * Hides the marker overlay.
     */
    fun hideMarker() {
        if (isDestroyed) return

        postToMain {
            stopPulse()
            mainHandler.removeCallbacks(autoHideRunnable)
            detachIfNeeded()
        }
    }

    /**
     * Permanently destroys the marker overlay.
     */
    fun destroy() {
        postToMain {
            stopPulse()
            mainHandler.removeCallbacks(autoHideRunnable)
            detachIfNeeded()
            isDestroyed = true
            Log.i(TAG, "ElementMarkerOverlay destroyed ✓")
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    private fun startPulse() {
        stopPulse()
        pulseAnimator = ObjectAnimator.ofFloat(markerView, "alpha", 1.0f, 0.5f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        markerView.alpha = 1.0f
    }

    private fun attachIfNeeded() {
        if (isAdded) return
        try {
            windowManager.addView(markerView, layoutParams)
            isAdded = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach marker: ${e.message}")
        }
    }

    private fun detachIfNeeded() {
        if (!isAdded) return
        try {
            windowManager.removeView(markerView)
            isAdded = false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach marker: ${e.message}")
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val isAccessibilityContext = context is android.accessibilityservice.AccessibilityService

        val overlayType = if (isAccessibilityContext && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        return params
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }

    // ── Inner View ──────────────────────────────────────────────────────────

    /**
     * Custom View that draws only a rounded-rectangle marker at [targetBounds].
     * The rest of the view is fully transparent.
     */
    private class MarkerView(context: Context) : View(context) {

        var targetBounds: RectF? = null

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#4CAF50")  // Material Green
            strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, BORDER_WIDTH_DP,
                context.resources.displayMetrics
            )
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(38, 76, 175, 80)  // 15% opacity green fill
        }

        private val cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS_DP,
            context.resources.displayMetrics
        )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val bounds = targetBounds ?: return

            // Draw subtle fill
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
            // Draw border
            canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
        }
    }
}
