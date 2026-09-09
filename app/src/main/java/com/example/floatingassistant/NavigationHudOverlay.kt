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
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * NavigationHudOverlay — Breadcrumb Trail Edition
 *
 * Layout (top-to-bottom inside the pill):
 *   1. breadcrumbLabel  — dim, monospace:  "Home → [Clock] → Alarm"
 *   2. actionLabel      — bold white:      "Tap 'Alarm'"
 *   3. contextLabel     — dim grey:        "Current: Clock-Alarm | Expected: Alarm"
 *   4. buttonContainer  — YES / NO (shown only for destination verification)
 */
class NavigationHudOverlay(private val context: Context) {

    companion object {
        private const val TAG             = "[NavigationEngine]"
        private const val TOP_MARGIN_DP   = 36
        private const val H_PADDING_DP    = 18
        private const val V_PADDING_DP    = 10
        private const val CORNER_RADIUS_DP = 22f
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

    @Volatile private var isDestroyed = false
    private val layoutParams: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    init {
        breadcrumbLabel = TextView(context).apply {
            textSize      = 10f
            setTextColor(Color.parseColor("#606060"))
            typeface      = Typeface.MONOSPACE
            gravity       = Gravity.CENTER_HORIZONTAL
            letterSpacing = 0.05f
            text          = ""
        }

        headerLabel = TextView(context).apply {
            textSize      = 10f
            setTextColor(Color.parseColor("#A0A0A0"))
            typeface      = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            gravity       = Gravity.CENTER_HORIZONTAL
            text          = ""
        }

        actionLabel = TextView(context).apply {
            textSize  = 14f
            setTextColor(Color.WHITE)
            typeface  = Typeface.DEFAULT_BOLD
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        contextLabel = TextView(context).apply {
            textSize  = 10f
            setTextColor(Color.parseColor("#707070"))
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
        }

        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_HORIZONTAL
            visibility  = android.view.View.GONE
            setPadding(0, dpToPx(8), 0, 0)
        }

        hudContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(H_PADDING_DP), dpToPx(V_PADDING_DP), dpToPx(H_PADDING_DP), dpToPx(V_PADDING_DP))
            background  = buildPillDrawable()
            gravity     = Gravity.CENTER_HORIZONTAL
            addView(breadcrumbLabel)
            addView(headerLabel)
            addView(actionLabel)
            addView(contextLabel)
            addView(buttonContainer)
        }

        Log.d(TAG, "NavigationHudOverlay: view hierarchy built ✓")
    }

    /**
     * Main update method.
     *
     * @param stepHeader        Small dim text above the action ("STEP 2 OF 4", "WRONG APP")
     * @param actionInstruction Large bold white instruction ("Tap 'Settings'")
     * @param breadcrumb        Optional trail string ("Home → [Clock] → Alarm")
     * @param contextText       Optional dim debug line ("Current: Clock-Alarm | Expected: Alarm")
     * @param subTag            Logcat context label
     */
    fun updateHud(
        stepHeader:        String,
        actionInstruction: String,
        breadcrumb:        String? = null,
        contextText:       String? = null,
        subTag:            String? = null
    ) {
        if (isDestroyed) { Log.w(TAG, "updateHud() after destroy — ignoring"); return }
        Log.d(TAG, "HUD [$subTag] header='$stepHeader' action='$actionInstruction' crumb='$breadcrumb' ctx='$contextText'")

        postToMain {
            if (!breadcrumb.isNullOrBlank()) {
                breadcrumbLabel.text       = breadcrumb
                breadcrumbLabel.visibility = android.view.View.VISIBLE
            } else {
                breadcrumbLabel.visibility = android.view.View.GONE
            }

            headerLabel.text = stepHeader.uppercase()

            actionLabel.text = actionInstruction

            if (!contextText.isNullOrBlank()) {
                contextLabel.text       = contextText
                contextLabel.visibility = android.view.View.VISIBLE
            } else {
                contextLabel.visibility = android.view.View.GONE
            }

            // Hide verification buttons on every normal update
            buttonContainer.visibility = android.view.View.GONE
            buttonContainer.removeAllViews()

            attachIfNeeded()
        }
    }

    fun showVerificationPrompt(onYes: () -> Unit, onNo: () -> Unit) {
        if (isDestroyed) return
        Log.d(TAG, "HUD — Showing verification prompt")

        postToMain {
            breadcrumbLabel.visibility = android.view.View.GONE
            headerLabel.text           = "DESTINATION REACHED"
            actionLabel.text           = "Did we find what you were looking for?"
            contextLabel.visibility    = android.view.View.GONE

            buttonContainer.removeAllViews()

            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpToPx(8), 0, dpToPx(8), 0) }

            val yesBtn = Button(context).apply {
                text = "YES ✓"
                setOnClickListener { onYes() }
            }
            val noBtn = Button(context).apply {
                text = "NO ✗"
                setOnClickListener { onNo() }
            }

            buttonContainer.addView(yesBtn, btnParams)
            buttonContainer.addView(noBtn,  btnParams)
            buttonContainer.visibility = android.view.View.VISIBLE

            // Enable touch / focus so buttons are clickable
            layoutParams.flags = layoutParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            if (hudContainer.isAttachedToWindow) {
                windowManager.updateViewLayout(hudContainer, layoutParams)
            } else {
                attachIfNeeded()
            }
        }
    }

    fun resetFocusability() {
        postToMain {
            layoutParams.flags = layoutParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (hudContainer.isAttachedToWindow) {
                windowManager.updateViewLayout(hudContainer, layoutParams)
            }
        }
    }

    fun show() {
        if (isDestroyed) { Log.w(TAG, "show() after destroy — ignoring"); return }
        postToMain { attachIfNeeded() }
    }

    fun hide() { postToMain { detachIfNeeded() } }

    fun destroy() {
        postToMain {
            detachIfNeeded()
            isDestroyed = true
            Log.i(TAG, "NavigationHudOverlay destroyed ✓")
        }
    }

    private fun attachIfNeeded() {
        if (hudContainer.isAttachedToWindow) return
        try { windowManager.addView(hudContainer, layoutParams) }
        catch (e: Exception) { Log.e(TAG, "Failed to attach HUD: ${e.message}") }
    }

    private fun detachIfNeeded() {
        if (!hudContainer.isAttachedToWindow) return
        try { windowManager.removeView(hudContainer) }
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
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE   or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType, flags, PixelFormat.TRANSLUCENT
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
