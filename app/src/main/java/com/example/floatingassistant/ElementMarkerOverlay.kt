package com.example.floatingassistant

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
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

    enum class ScrollDirection {
        UP,
        DOWN
    }

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
     * [PERMANENT FIX] Do not remove this! Clamps bounds to the visible screen area
     * above the phone's 3-button navigation bar so it never draws over system navigation controls,
     * while allowing unclipped display when targeting system navigation bar buttons (isSystemNav == true).
     */
    fun showMarker(bounds: Rect, isSystemNav: Boolean = false) {
        if (isDestroyed) return

        postToMain {
            val (topLimit, bottomLimit) = getVisibleBounds()
            val pad = dpToPx(PADDING_DP.toInt())

            val rawTop = (bounds.top - pad).toFloat()
            val rawBottom = (bounds.bottom + pad).toFloat()

            // If the element is normal app content pushed completely behind the navigation bar or status bar, hide marker.
            // Bypassed when isSystemNav == true because system navigation controls naturally live in the navbar area.
            if (!isSystemNav && (rawTop >= bottomLimit - dpToPx(8) || rawBottom <= topLimit + dpToPx(8))) {
                hideMarker()
                return@postToMain
            }

            // Clamp bounds strictly to visible content area (or allow full screen bounds for system nav)
            val clampedLeft = (bounds.left - pad).toFloat().coerceAtLeast(0f)
            val clampedTop = if (isSystemNav) rawTop else rawTop.coerceAtLeast(topLimit)
            val clampedRight = (bounds.right + pad).toFloat().coerceAtMost(context.resources.displayMetrics.widthPixels.toFloat())
            val clampedBottom = if (isSystemNav) rawBottom else rawBottom.coerceAtMost(bottomLimit)

            markerView.arrowDirection = null
            markerView.isSystemNav = isSystemNav
            markerView.topLimit = if (isSystemNav) 0f else topLimit
            markerView.bottomLimit = if (isSystemNav) Float.MAX_VALUE else bottomLimit
            markerView.targetBounds = RectF(
                clampedLeft,
                clampedTop,
                clampedRight,
                clampedBottom
            )
            markerView.invalidate()

            attachIfNeeded()

            // Start pulse animation
            startPulse()
            // FIX: Removed Log.d spam for Marker bounds. Do not add it back.
        }
    }

    /**
     * Shows a visual directional scroll arrow in the center of the screen
     * using the same green color styling as the target highlight box.
     * [PERMANENT FIX] Do not remove this! Guides the user visually to scroll UP or DOWN.
     */
    fun showScrollArrow(direction: ScrollDirection) {
        if (isDestroyed) return

        postToMain {
            val (topLimit, bottomLimit) = getVisibleBounds()
            markerView.targetBounds = null
            markerView.arrowDirection = direction
            markerView.topLimit = topLimit
            markerView.bottomLimit = bottomLimit
            markerView.invalidate()

            attachIfNeeded()
            startPulse()
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
            markerView.targetBounds = null
            markerView.arrowDirection = null
            markerView.isSystemNav = false
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

    /**
     * Calculates user-visible screen boundaries (excluding top status bar and bottom navigation bar).
     * [PERMANENT FIX] Do not remove this! Ensures the visual highlight stays strictly inside the
     * visible app viewport and never draws over the phone's 3-button navigation bar (Back, Home, Recents).
     */
    private fun getVisibleBounds(): Pair<Float, Float> {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars()
                )
                val topLimit = insets.top.toFloat()
                val bottomLimit = (metrics.bounds.bottom - insets.bottom).toFloat()
                Pair(topLimit, bottomLimit)
            } else {
                val res = context.resources
                val navId = res.getIdentifier("navigation_bar_height", "dimen", "android")
                val statusId = res.getIdentifier("status_bar_height", "dimen", "android")
                val navHeight = if (navId > 0) res.getDimensionPixelSize(navId) else 0
                val statusHeight = if (statusId > 0) res.getDimensionPixelSize(statusId) else 0
                val bottomLimit = (res.displayMetrics.heightPixels - navHeight).toFloat()
                Pair(statusHeight.toFloat(), bottomLimit)
            }
        } catch (_: Exception) {
            Pair(0f, context.resources.displayMetrics.heightPixels.toFloat())
        }
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
        var arrowDirection: ScrollDirection? = null
        var topLimit: Float = 0f
        var bottomLimit: Float = Float.MAX_VALUE
        var isSystemNav: Boolean = false

        private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#4CAF50")  // Material Green
            strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, BORDER_WIDTH_DP,
                context.resources.displayMetrics
            )
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(38, 76, 175, 80)  // 15% opacity green fill
        }

        private val arrowBadgeBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(200, 20, 26, 20)  // Semi-transparent dark circular badge for high contrast
        }

        private val arrowBadgeBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.parseColor("#4CAF50")
            strokeWidth = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, 2.5f,
                context.resources.displayMetrics
            )
        }

        private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.parseColor("#4CAF50")  // Solid material green arrow
        }

        private val cornerRadius = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, CORNER_RADIUS_DP,
            context.resources.displayMetrics
        )

        private fun dp(dpValue: Float): Float =
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, dpValue,
                context.resources.displayMetrics
            )

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            // [PERMANENT FIX] Do not remove this! Canvas clip ensures the marker rectangle
            // and semi-transparent fill never bleed over the phone's 3-button system navigation bar or status bar,
            // while allowing system navigation controls to be drawn when isSystemNav is true.
            canvas.save()
            if (!isSystemNav && (bottomLimit < Float.MAX_VALUE || topLimit > 0f)) {
                canvas.clipRect(0f, topLimit, width.toFloat(), bottomLimit)
            }

            // Case 1: Draw target bounding box
            val bounds = targetBounds
            if (bounds != null) {
                // Draw subtle fill
                canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, fillPaint)
                // Draw border
                canvas.drawRoundRect(bounds, cornerRadius, cornerRadius, borderPaint)
            }

            // Case 2: Draw centered directional scroll arrow
            val dir = arrowDirection
            if (dir != null) {
                drawCenterArrow(canvas, dir)
            }

            canvas.restore()
        }

        private fun drawCenterArrow(canvas: Canvas, direction: ScrollDirection) {
            val cx = width / 2f
            val visibleMaxBottom = if (bottomLimit < Float.MAX_VALUE) bottomLimit else height.toFloat()
            val cy = (topLimit + visibleMaxBottom) / 2f

            val badgeRadius = dp(44f)

            // 1. Draw circular backing badge with green border
            canvas.drawCircle(cx, cy, badgeRadius, arrowBadgeBgPaint)
            canvas.drawCircle(cx, cy, badgeRadius, arrowBadgeBorderPaint)

            // 2. Draw directional green arrow matching target box color
            val stemW = dp(6f)
            val headW = dp(16f)
            val path = Path()

            when (direction) {
                ScrollDirection.DOWN -> {
                    val topY = cy - dp(16f)
                    val midY = cy + dp(4f)
                    val tipY = cy + dp(18f)

                    path.moveTo(cx - stemW, topY)
                    path.lineTo(cx + stemW, topY)
                    path.lineTo(cx + stemW, midY)
                    path.lineTo(cx + headW, midY)
                    path.lineTo(cx, tipY)
                    path.lineTo(cx - headW, midY)
                    path.lineTo(cx - stemW, midY)
                    path.close()
                }
                ScrollDirection.UP -> {
                    val tipY = cy - dp(18f)
                    val midY = cy - dp(4f)
                    val botY = cy + dp(16f)

                    path.moveTo(cx, tipY)
                    path.lineTo(cx + headW, midY)
                    path.lineTo(cx + stemW, midY)
                    path.lineTo(cx + stemW, botY)
                    path.lineTo(cx - stemW, botY)
                    path.lineTo(cx - stemW, midY)
                    path.lineTo(cx - headW, midY)
                    path.close()
                }
            }

            canvas.drawPath(path, arrowFillPaint)
            canvas.drawPath(path, borderPaint)
        }
    }
}
