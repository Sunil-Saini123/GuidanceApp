package com.example.floatingassistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * NavigationHudOverlay — Track 4 / Stage 1 (Bug-fixed)
 *
 * Fixes applied over the original Stage 1 implementation:
 *
 * BUG-1 (Blank text): The original `updateHud()` posted text assignment then called `show()`
 *   which itself also posted — meaning `stepLabel`/`actionLabel` were null when text was set
 *   because `ensureViewCreated()` had not yet executed. Fix: `ensureViewCreated()` is now
 *   called eagerly in the constructor (still on the caller's thread) so view refs are always
 *   non-null before any text is applied. `show()` then just calls `addView` on the already-built
 *   view, and `updateHud()` sets text + calls `show()` in a single atomic main-thread block.
 *
 * BUG-4 (Inconsistent show/hide): Replaced the @Volatile `isAttached` flag with a real
 *   `hudContainer.isAttachedToWindow` check so stale-flag races after WindowManager
 *   IllegalArgumentException can never leave the HUD in an inconsistent state.
 *
 * All WindowManager calls remain confined to the main thread via [mainHandler].
 */
class NavigationHudOverlay(private val context: Context) {

    companion object {
        private const val TAG = "[NavigationEngine]"

        private const val TOP_MARGIN_DP    = 36
        private const val H_PADDING_DP     = 16
        private const val V_PADDING_DP     = 10
        private const val CORNER_RADIUS_DP = 20f
        private const val STROKE_WIDTH_DP  = 1
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())

    // ── Views — built eagerly in constructor so refs are always non-null ───────
    private val hudContainer: LinearLayout
    private val stepLabel:    TextView
    private val actionLabel:  TextView

    // ── True only after addView succeeds; cleared on removeView ───────────────
    @Volatile private var isDestroyed = false

    private val layoutParams: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    init {
        // Build views immediately on whichever thread constructs this object.
        // We do NOT add to WindowManager here; that is deferred to show().
        val hPad = dpToPx(H_PADDING_DP)
        val vPad = dpToPx(V_PADDING_DP)

        stepLabel = TextView(context).apply {
            textSize      = 11f
            setTextColor(Color.parseColor("#A0A0A0"))
            typeface      = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity       = Gravity.CENTER_HORIZONTAL
            text          = ""
        }

        actionLabel = TextView(context).apply {
            textSize  = 14f
            setTextColor(Color.WHITE)
            typeface  = android.graphics.Typeface.DEFAULT_BOLD
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
        }

        hudContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            background  = buildPillDrawable()
            gravity     = Gravity.CENTER_HORIZONTAL
            addView(stepLabel)
            addView(actionLabel)
        }

        Log.d(TAG, "NavigationHudOverlay: view hierarchy built in constructor ✓")
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Public API
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Set text on both labels and make the HUD visible (attach if not already).
     *
     * BUG-1 fix: text is set AND addView happens inside a single `postToMain` block,
     * guaranteeing the view is populated before it is ever measured by the framework.
     */
    fun updateHud(
        stepHeader: String,
        actionInstruction: String,
        subTag: String? = null
    ) {
        if (isDestroyed) {
            Log.w(TAG, "updateHud() called after destroy() — ignoring")
            return
        }
        val tagSuffix = if (subTag != null) " | $subTag" else ""
        Log.d(TAG, "HUD Updated -> Header: '$stepHeader' | Action: '$actionInstruction'$tagSuffix")

        postToMain {
            // 1. Apply text first (view refs are always non-null — built in init{})
            stepLabel.text   = stepHeader.uppercase()
            actionLabel.text = actionInstruction

            // 2. Show if not already on screen
            attachIfNeeded()
        }
    }

    /**
     * Attach the HUD to WindowManager.
     * No-op if already attached. Thread-safe.
     */
    fun show() {
        if (isDestroyed) {
            Log.w(TAG, "show() called after destroy() — ignoring")
            return
        }
        postToMain { attachIfNeeded() }
    }

    /**
     * Remove the HUD from WindowManager.
     * Uses `isAttachedToWindow` (real system state) instead of a flag.
     * Thread-safe.
     */
    fun hide() {
        postToMain { detachIfNeeded() }
    }

    /**
     * Permanent teardown. Call from FloatingOverlayService.onDestroy().
     */
    fun destroy() {
        postToMain {
            detachIfNeeded()
            isDestroyed = true
            Log.i(TAG, "NavigationHudOverlay destroyed ✓")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Internal helpers
    // ══════════════════════════════════════════════════════════════════════════

    /** BUG-4 fix: use the real system state rather than a mirrored flag. */
    private fun attachIfNeeded() {
        if (hudContainer.isAttachedToWindow) {
            Log.d(TAG, "attachIfNeeded: already attached — no-op")
            return
        }
        try {
            windowManager.addView(hudContainer, layoutParams)
            Log.i(TAG, "HUD attached to WindowManager ✓ [TOP|CENTER_HORIZONTAL, topMargin=${TOP_MARGIN_DP}dp]")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach HUD: ${e.message}", e)
        }
    }

    private fun detachIfNeeded() {
        if (!hudContainer.isAttachedToWindow) {
            Log.d(TAG, "detachIfNeeded: not attached — no-op")
            return
        }
        try {
            windowManager.removeView(hudContainer)
            Log.i(TAG, "HUD detached from WindowManager ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to detach HUD: ${e.message}", e)
        }
    }

    private fun buildPillDrawable(): GradientDrawable {
        val fillColor   = Color.argb(230, 24, 24, 24)
        val strokeColor = Color.argb(51, 255, 255, 255)
        val cornerPx    = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
        val strokePx    = dpToPx(STROKE_WIDTH_DP)
        return GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx
            setColor(fillColor)
            setStroke(strokePx, strokeColor)
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        val overlayType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            flags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y       = dpToPx(TOP_MARGIN_DP)
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
}
