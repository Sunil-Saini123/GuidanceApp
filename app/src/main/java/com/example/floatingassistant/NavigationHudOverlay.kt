package com.example.floatingassistant

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * NavigationHudOverlay — Color-Coded Breadcrumb Trail Edition
 *
 * Layout (top-to-bottom inside the pill):
 *   1. breadcrumbLabel  — dim, monospace:  "Home → [Clock] → Alarm"
 *   2. headerLabel      — small caps:      "STEP 2 OF 4"
 *   3. actionLabel      — bold white:      "Tap 'Alarm'"
 *   4. contextLabel     — dim grey:        "Found on screen"
 *   5. buttonContainer  — Reroute / YES / NO buttons
 *
 * The pill background color changes based on [HudState]:
 *   FOUND      → green  — element is on screen, tap it
 *   SEARCHING  → orange — looking for element / scroll needed
 *   ON_TRACK   → dark   — normal navigation step
 *   OFF_TRACK  → red    — wrong app / wrong path
 *   SUCCESS    → green  — destination reached
 */
class NavigationHudOverlay(private val context: Context) {

    /**
     * Visual state of the HUD pill. Each state maps to a distinct background color
     * so the user can instantly see navigation status at a glance.
     */
    enum class HudState {
        /** Element found on screen — tap it! Green pill. */
        FOUND,
        /** Looking for element / scrolling needed. Orange pill. */
        SEARCHING,
        /** Normal navigation step, on the correct screen. Dark pill. */
        ON_TRACK,
        /** Wrong app / wrong path / go back. Red pill. */
        OFF_TRACK,
        /** Destination reached successfully. Green pill. */
        SUCCESS
    }

    companion object {
        private const val TAG             = "[NavigationEngine]"
        private const val TOP_MARGIN_DP   = 4
        private const val H_PADDING_DP    = 14
        private const val V_PADDING_DP    = 6
        private const val CORNER_RADIUS_DP = 20f
        private const val STROKE_WIDTH_DP  = 1
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler   = Handler(Looper.getMainLooper())

    private val hudContainer:    LinearLayout
    private val breadcrumbLabel: TextView   // Line 1 — path trail
    private val headerLabel:     TextView   // Line 2a — step header (e.g. "STEP 2 OF 4")
    private val actionLabel:     TextView   // Line 2b — main instruction
    private val contextLabel:    TextView   // Line 3 — debug / context
    private val buttonContainer: LinearLayout

    /** The pill background — kept as a field so we can update its color without rebuilding. */
    private val pillDrawable: GradientDrawable

    @Volatile private var isDestroyed = false
    private val layoutParams: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    init {
        pillDrawable = buildPillDrawable()

        breadcrumbLabel = TextView(context).apply {
            textSize      = 8f
            setTextColor(Color.parseColor("#909090"))
            typeface      = Typeface.MONOSPACE
            gravity       = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.04f
            text          = ""
        }

        headerLabel = TextView(context).apply {
            textSize      = 9f
            setTextColor(Color.parseColor("#B0B0B0"))
            typeface      = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
            gravity       = Gravity.CENTER_HORIZONTAL
            text          = ""
        }

        actionLabel = TextView(context).apply {
            textSize  = 13f
            setTextColor(Color.WHITE)
            typeface  = Typeface.DEFAULT_BOLD
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
            setPadding(0, dpToPx(1), 0, dpToPx(1))
        }

        contextLabel = TextView(context).apply {
            textSize  = 9f
            setTextColor(Color.parseColor("#909090"))
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
        }

        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_HORIZONTAL
            visibility  = View.GONE
            setPadding(0, dpToPx(4), 0, 0)
        }

        hudContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(H_PADDING_DP), dpToPx(V_PADDING_DP), dpToPx(H_PADDING_DP), dpToPx(V_PADDING_DP))
            background  = pillDrawable
            gravity     = Gravity.CENTER_HORIZONTAL
            addView(breadcrumbLabel)
            addView(headerLabel)
            addView(actionLabel)
            addView(contextLabel)
            addView(buttonContainer)
        }

        Log.d(TAG, "NavigationHudOverlay: view hierarchy built ✓")
    }

    // ── Color palette ────────────────────────────────────────────────────────

    private fun updatePillColor(state: HudState) {
        when (state) {
            HudState.FOUND, HudState.SUCCESS -> {
                pillDrawable.setColor(Color.argb(240, 20, 60, 20))       // dark green bg
                pillDrawable.setStroke(dpToPx(STROKE_WIDTH_DP), Color.parseColor("#4CAF50"))
            }
            HudState.SEARCHING -> {
                pillDrawable.setColor(Color.argb(240, 50, 30, 0))        // dark orange bg
                pillDrawable.setStroke(dpToPx(STROKE_WIDTH_DP), Color.parseColor("#FF9800"))
            }
            HudState.OFF_TRACK -> {
                pillDrawable.setColor(Color.argb(240, 60, 15, 15))       // dark red bg
                pillDrawable.setStroke(dpToPx(STROKE_WIDTH_DP), Color.parseColor("#F44336"))
            }
            HudState.ON_TRACK -> {
                pillDrawable.setColor(Color.argb(235, 20, 20, 20))       // default dark
                pillDrawable.setStroke(dpToPx(STROKE_WIDTH_DP), Color.argb(55, 255, 255, 255))
            }
        }
        pillDrawable.invalidateSelf()
    }

    // ── Main update method ───────────────────────────────────────────────────

    /**
     * @param stepHeader        Small dim text above the action ("STEP 2 OF 4", "WRONG APP")
     * @param actionInstruction Large bold white instruction ("Tap 'Settings'")
     * @param breadcrumb        Optional trail string ("Home → [Clock] → Alarm")
     * @param contextText       Optional dim debug line ("Current: Clock-Alarm | Expected: Alarm")
     * @param subTag            Logcat context label
     * @param hudState          Visual state controlling pill background color
     * @param onHelpRequested   If non-null, shows a compact Reroute button
     */
    fun updateHud(
        stepHeader:        String,
        actionInstruction: String,
        breadcrumb:        String? = null,
        contextText:       String? = null,
        subTag:            String? = null,
        hudState:          HudState = HudState.ON_TRACK,
        onHelpRequested:   (() -> Unit)? = null
    ) {
        if (isDestroyed) { Log.w(TAG, "updateHud() after destroy — ignoring"); return }
        Log.d(TAG, "HUD [$subTag] header='$stepHeader' action='$actionInstruction' crumb='$breadcrumb' ctx='$contextText'")

        postToMain {
            // Update pill color
            updatePillColor(hudState)

            if (!breadcrumb.isNullOrBlank()) {
                breadcrumbLabel.text       = breadcrumb
                breadcrumbLabel.visibility = View.VISIBLE
            } else {
                breadcrumbLabel.visibility = View.GONE
            }

            headerLabel.text = stepHeader.uppercase()

            actionLabel.text = actionInstruction

            if (!contextText.isNullOrBlank()) {
                contextLabel.text       = contextText
                contextLabel.visibility = View.VISIBLE
            } else {
                contextLabel.visibility = View.GONE
            }

            buttonContainer.removeAllViews()
            if (onHelpRequested != null) {
                val rerouteBtn = Button(context).apply {
                    text = "↻ Reroute"
                    textSize = 10f
                    setTextColor(Color.parseColor("#FFB74D"))
                    typeface = Typeface.DEFAULT_BOLD
                    isAllCaps = false
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dpToPx(12).toFloat()
                        setColor(Color.argb(180, 50, 35, 0))
                        setStroke(dpToPx(1), Color.parseColor("#FF9800"))
                    }
                    setPadding(dpToPx(12), dpToPx(3), dpToPx(12), dpToPx(3))
                    minimumHeight = 0
                    minHeight = 0
                    setOnClickListener { onHelpRequested() }
                }
                val btnParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, dpToPx(4), 0, 0) }
                buttonContainer.addView(rerouteBtn, btnParams)
                buttonContainer.visibility = View.VISIBLE
            } else {
                buttonContainer.visibility = View.GONE
            }

            attachIfNeeded()

            // Force the WindowManager to remeasure — some OEMs (ColorOS) don't
            // automatically resize WRAP_CONTENT overlay views when child text changes.
            if (isAdded) {
                try { windowManager.updateViewLayout(hudContainer, layoutParams) }
                catch (_: Exception) { /* view not attached yet */ }
            }
        }
    }

    fun showVerificationPrompt(onYes: () -> Unit, onNo: () -> Unit) {
        if (isDestroyed) return
        Log.d(TAG, "HUD — Showing verification prompt")

        postToMain {
            updatePillColor(HudState.SUCCESS)

            breadcrumbLabel.visibility = View.GONE
            headerLabel.text           = "DESTINATION REACHED"
            actionLabel.text           = "Did we find what you were looking for?"
            contextLabel.visibility    = View.GONE

            buttonContainer.removeAllViews()

            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpToPx(6), 0, dpToPx(6), 0) }

            val yesBtn = Button(context).apply {
                text = "YES ✓"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor("#388E3C"))
                }
                setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
                minimumHeight = 0
                minHeight = 0
                setOnClickListener { onYes() }
            }
            val noBtn = Button(context).apply {
                text = "NO ✗"
                textSize = 11f
                setTextColor(Color.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                isAllCaps = false
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12).toFloat()
                    setColor(Color.parseColor("#C62828"))
                }
                setPadding(dpToPx(16), dpToPx(4), dpToPx(16), dpToPx(4))
                minimumHeight = 0
                minHeight = 0
                setOnClickListener { onNo() }
            }

            buttonContainer.addView(yesBtn, btnParams)
            buttonContainer.addView(noBtn,  btnParams)
            buttonContainer.visibility = View.VISIBLE

            if (isAdded) {
                windowManager.updateViewLayout(hudContainer, layoutParams)
            } else {
                attachIfNeeded()
            }
        }
    }

    fun resetFocusability() {
        // No-op. We no longer steal focus.
    }

    fun show() {
        if (isDestroyed) { Log.w(TAG, "show() after destroy — ignoring"); return }
        postToMain {
            attachIfNeeded()
            Log.d(TAG, "show() — isAdded=$isAdded attached=${hudContainer.isAttachedToWindow} visible=${hudContainer.visibility == View.VISIBLE}")
        }
    }

    fun hide() { postToMain { detachIfNeeded() } }

    fun destroy() {
        postToMain {
            detachIfNeeded()
            isDestroyed = true
            Log.i(TAG, "NavigationHudOverlay destroyed ✓")
        }
    }

    @Volatile private var isAdded = false

    private fun attachIfNeeded() {
        if (isAdded) return
        try { 
            windowManager.addView(hudContainer, layoutParams)
            isAdded = true
        }
        catch (e: Exception) { Log.e(TAG, "Failed to attach HUD: ${e.message}") }
    }

    private fun detachIfNeeded() {
        if (!isAdded) return
        try { 
            windowManager.removeView(hudContainer)
            isAdded = false
        }
        catch (e: Exception) { Log.e(TAG, "Failed to detach HUD: ${e.message}") }
    }

    private fun buildPillDrawable(): GradientDrawable {
        return GradientDrawable().apply {
            shape        = GradientDrawable.RECTANGLE
            cornerRadius = dpToPx(CORNER_RADIUS_DP.toInt()).toFloat()
            setColor(Color.argb(235, 20, 20, 20))
            setStroke(dpToPx(STROKE_WIDTH_DP), Color.argb(55, 255, 255, 255))
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams {
        // TYPE_ACCESSIBILITY_OVERLAY (API 22+) sits above ALL other windows,
        // including system Settings sub-pages on ColorOS/OxygenOS/MIUI that
        // hide regular TYPE_APPLICATION_OVERLAY views.
        val isAccessibilityContext = context is android.accessibilityservice.AccessibilityService
        
        val overlayType = if (isAccessibilityContext && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1)
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // [PERMANENT FIX] Do not remove this! Offset y by status bar height so the HUD pill
            // sits cleanly below camera punch holes (e.g. Realme RMX3870) and status bar icons,
            // without blocking swipe-down gestures for the notification shade.
            y = getStatusBarHeight() + dpToPx(TOP_MARGIN_DP)
        }

        // [PERMANENT FIX] Do not remove this! Handle display cutouts cleanly without letterboxing or jank
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        return params
    }

    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = context.resources.getDimensionPixelSize(resourceId)
        }
        return if (result > 0) result else dpToPx(36)
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
