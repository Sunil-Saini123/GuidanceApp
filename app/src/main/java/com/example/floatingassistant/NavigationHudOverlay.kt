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
import android.widget.Button

/**
 * NavigationHudOverlay — Track 4 / Stage 2.6 + Stage 4
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

    private val hudContainer: LinearLayout
    private val stepLabel:    TextView
    private val actionLabel:  TextView
    private val contextLabel: TextView
    private val buttonContainer: LinearLayout
    
    @Volatile private var isDestroyed = false

    private val layoutParams: WindowManager.LayoutParams by lazy { buildLayoutParams() }

    init {
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
            setPadding(0, dpToPx(2), 0, dpToPx(2))
        }

        contextLabel = TextView(context).apply {
            textSize  = 10f
            setTextColor(Color.parseColor("#808080"))
            gravity   = Gravity.CENTER_HORIZONTAL
            text      = ""
        }
        
        buttonContainer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = android.view.View.GONE
            setPadding(0, dpToPx(8), 0, 0)
        }

        hudContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(hPad, vPad, hPad, vPad)
            background  = buildPillDrawable()
            gravity     = Gravity.CENTER_HORIZONTAL
            addView(stepLabel)
            addView(actionLabel)
            addView(contextLabel)
            addView(buttonContainer)
        }

        Log.d(TAG, "NavigationHudOverlay: view hierarchy built in constructor ✓")
    }

    fun updateHud(
        stepHeader: String,
        actionInstruction: String,
        contextText: String? = null,
        subTag: String? = null
    ) {
        if (isDestroyed) {
            Log.w(TAG, "updateHud() called after destroy() — ignoring")
            return
        }
        val tagSuffix = if (subTag != null) " | $subTag" else ""
        Log.d(TAG, "HUD Updated -> Header: '$stepHeader' | Action: '$actionInstruction' | Context: '$contextText'$tagSuffix")

        postToMain {
            stepLabel.text   = stepHeader.uppercase()
            actionLabel.text = actionInstruction
            
            if (contextText.isNullOrEmpty()) {
                contextLabel.visibility = android.view.View.GONE
            } else {
                contextLabel.visibility = android.view.View.VISIBLE
                contextLabel.text = contextText
            }
            
            buttonContainer.visibility = android.view.View.GONE
            buttonContainer.removeAllViews()

            attachIfNeeded()
        }
    }
    
    fun showVerificationPrompt(onYes: () -> Unit, onNo: () -> Unit) {
        if (isDestroyed) return
        Log.d(TAG, "HUD Updated -> Verification Prompt")
        
        postToMain {
            stepLabel.text = "DESTINATION REACHED"
            actionLabel.text = "Did we find what you were looking for?"
            contextLabel.visibility = android.view.View.GONE
            
            buttonContainer.removeAllViews()
            
            val yesBtn = Button(context).apply {
                text = "YES"
                setOnClickListener { onYes() }
            }
            val noBtn = Button(context).apply {
                text = "NO"
                setOnClickListener { onNo() }
            }
            
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dpToPx(8), 0, dpToPx(8), 0) }
            
            buttonContainer.addView(yesBtn, params)
            buttonContainer.addView(noBtn, params)
            buttonContainer.visibility = android.view.View.VISIBLE
            
            // Allow focus and touch events for buttons by updating layout params
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
        if (isDestroyed) {
            Log.w(TAG, "show() called after destroy() — ignoring")
            return
        }
        postToMain { attachIfNeeded() }
    }

    fun hide() {
        postToMain { detachIfNeeded() }
    }

    fun destroy() {
        postToMain {
            detachIfNeeded()
            isDestroyed = true
            Log.i(TAG, "NavigationHudOverlay destroyed ✓")
        }
    }

    private fun attachIfNeeded() {
        if (hudContainer.isAttachedToWindow) {
            return
        }
        try {
            windowManager.addView(hudContainer, layoutParams)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach HUD: ${e.message}", e)
        }
    }

    private fun detachIfNeeded() {
        if (!hudContainer.isAttachedToWindow) {
            return
        }
        try {
            windowManager.removeView(hudContainer)
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
