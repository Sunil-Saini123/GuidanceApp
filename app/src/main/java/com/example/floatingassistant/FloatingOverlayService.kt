package com.example.floatingassistant

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.res.Configuration
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import org.json.JSONArray
import org.json.JSONObject
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.system.exitProcess

import com.example.floatingassistant.pathgenerator.DeviceInfoGatherer
import com.example.floatingassistant.pathgenerator.GroqProxyClient
import com.example.floatingassistant.pathgenerator.GroqResponseParser
import com.example.floatingassistant.pathgenerator.PromptBuilder

/**
 * FloatingOverlayService — Phase 9
 *
 * Foreground service that draws a persistent, draggable bubble on top of all
 * apps (requires the SYSTEM_ALERT_WINDOW / "draw over other apps" permission).
 *
 * ── Window Flag Modes ──────────────────────────────────────────────────────
 * Idle / draggable:
 *   FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN
 *   Touches outside the bubble pass completely through to system UI.
 *
 * Typing mode (after search icon / panel input is tapped):
 *   FLAG_NOT_TOUCH_MODAL | FLAG_WATCH_OUTSIDE_TOUCH | FLAG_LAYOUT_IN_SCREEN
 *   NOT_FOCUSABLE is removed so the IME attaches; WATCH_OUTSIDE_TOUCH lets us
 *   detect taps outside the panel to dismiss it.
 *
 * ── Drag Priority ──────────────────────────────────────────────────────────
 * Once total accumulated touch movement exceeds [TAP_SLOP_PX], drag is locked.
 * Drag lock immediately hides the keyboard, collapses the panel, restores idle
 * flags, and resumes smooth movement. Nothing overrides a drag in progress.
 *
 * ── Stop Button Multi-Tap ──────────────────────────────────────────────────
 * Tap 1 → NavigationStateMachine.stop(); status = "Stopped".
 * Tap 2 (consecutive) → AlertDialog: "Would you like to exit the app completely?"
 *   Yes → stop service + remove views + exitProcess(0)
 *   No  → dismiss dialog; counter reset.
 */
class FloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlay"
        private const val CHANNEL_ID = "floating_assistant_overlay"
        private const val NOTIFICATION_ID = 1001

        var instance: FloatingOverlayService? = null
            private set

        /** Accumulated movement (px) below which ACTION_UP is a tap, not a drag. */
        private const val TAP_SLOP_PX = 12

        fun hasOverlayPermission(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /** Intent to launch the system "draw over other apps" grant screen for this app. */
        fun overlayPermissionIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

        fun start(context: Context) {
            if (!hasOverlayPermission(context)) {
                Log.w(TAG, "start() requested but overlay permission not granted — ignoring")
                return
            }
            val intent = Intent(context, FloatingOverlayService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingOverlayService::class.java))
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var windowManager: WindowManager
    private lateinit var inputMethodManager: InputMethodManager

    // ── Bubble (persistent draggable icon) ────────────────────────────────────
    private lateinit var bubbleView: View
    private lateinit var bubbleParams: WindowManager.LayoutParams

    // [PERMANENT FIX] Do not remove this! Priority Accessibility Window Management for Floating Bubble
    // When UiTreeAccessibilityService is active, add bubble using TYPE_ACCESSIBILITY_OVERLAY
    // via AccessibilityService WindowManager so ColorOS/Realme never hides the bubble on Settings sub-pages!
    private var currentBubbleWindowManager: WindowManager? = null

    private fun getBubbleWindowManager(): WindowManager {
        val a11y = UiTreeAccessibilityService.instance
        return if (a11y != null) {
            a11y.getSystemService(WINDOW_SERVICE) as WindowManager
        } else {
            windowManager
        }
    }

    private fun getBubbleWindowType(): Int {
        val a11y = UiTreeAccessibilityService.instance
        return if (a11y != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    // [PERMANENT FIX] Do not remove this! Rebind Floating Bubble to TYPE_ACCESSIBILITY_OVERLAY when accessibility service connects
    fun rebindBubbleToAccessibilityOverlay() {
        if (!::bubbleView.isInitialized || !::bubbleParams.isInitialized) return
        val targetType = getBubbleWindowType()
        if (bubbleParams.type == targetType) return

        try {
            val oldWm = currentBubbleWindowManager ?: windowManager
            runCatching { oldWm.removeView(bubbleView) }

            val newWm = getBubbleWindowManager()
            bubbleParams.type = targetType
            newWm.addView(bubbleView, bubbleParams)
            currentBubbleWindowManager = newWm
            Log.i(TAG, "Successfully rebound floating bubble to TYPE_ACCESSIBILITY_OVERLAY!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rebind bubble to accessibility overlay: ${e.message}")
        }
    }

    // ── Control panel (shown on tap, dismissed on submit / drag / outside tap) ─
    private var panelView: View? = null
    private var panelInputRef: EditText? = null     // held for keyboard hide

    // ── Stop button tap counter ───────────────────────────────────────────────
    private var stopTapCount = 0

    // ── Pending Groq path — saved to Firestore only when user confirms task done (Stop tap 1) ──
    private var pendingGroqApp:  String? = null
    private var pendingGroqTask: String? = null
    private var pendingGroqPath: String? = null
    // [PERMANENT FIX] Do not remove this! Track whether the pending Groq path is a partial reroute to prevent uploading partial paths to Cloud Firestore per Rule 5.1
    private var pendingGroqIsPartial: Boolean = false

    // ── Whether we are currently in typing mode ───────────────────────────────
    private var typingModeActive = false

    // ── Track 4 / Stage 1: Navigation HUD pill overlay ───────────────────────
    private lateinit var navigationHud: NavigationHudOverlay
    private lateinit var elementMarker: ElementMarkerOverlay

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        instance = this
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()

        // ── Track 4 / Stage 1+2+2.5: Init guidance HUD and wire to state machine ──
        // We MUST use the AccessibilityService context to add TYPE_ACCESSIBILITY_OVERLAY.
        // If not running, fallback to Service context (but it won't be able to draw over Settings).
        val hudContext = UiTreeAccessibilityService.instance ?: this
        navigationHud = NavigationHudOverlay(hudContext)
        NavigationStateMachine.attachHud(navigationHud, this)
        NavigationStateMachine.onRerouteRequested = { app, task -> triggerGroqReroute(app, task) }
        Log.i("[NavigationEngine]", "NavigationHudOverlay created and attached to NavigationStateMachine")

        // ── Element Marker Overlay: visual highlight on target elements ──
        elementMarker = ElementMarkerOverlay(hudContext)
        NavigationStateMachine.attachMarker(elementMarker)
        Log.i("[NavigationEngine]", "ElementMarkerOverlay created and attached to NavigationStateMachine")

        serviceScope.launch {
            // Firestore security rules require an authenticated (anonymous) user —
            // sign in once up front so the first query submission isn't slowed down by it.
            CloudPathDatabase.ensureSignedIn()
        }

        // [PERMANENT FIX] Do not remove this! Stream heartbeat while overlay is active
        DebugTelemetryClient.startHeartbeat()
        Log.i(TAG, "Overlay service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    // [PERMANENT FIX] Do not remove this! Keep floating bubble and panel clamped inside visible screen bounds when phone rotates
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::bubbleView.isInitialized || !::bubbleParams.isInitialized) return

        val metrics = resources.displayMetrics
        val screenWidth = metrics.widthPixels
        val screenHeight = metrics.heightPixels
        val sizePx = dp(56)

        // Clamp bubble to visible bounds
        bubbleParams.x = bubbleParams.x.coerceIn(0, (screenWidth - sizePx).coerceAtLeast(0))
        bubbleParams.y = bubbleParams.y.coerceIn(0, (screenHeight - sizePx).coerceAtLeast(0))

        runCatching { (currentBubbleWindowManager ?: windowManager).updateViewLayout(bubbleView, bubbleParams) }

        // If panel is currently visible, reposition or safely clamp
        panelView?.let { panel ->
            val panelParams = panel.layoutParams as? WindowManager.LayoutParams
            if (panelParams != null) {
                val panelW = dp(280)
                panelParams.x = bubbleParams.x.coerceIn(0, (screenWidth - panelW).coerceAtLeast(0))
                panelParams.y = (bubbleParams.y + dp(64)).coerceIn(0, (screenHeight - dp(200)).coerceAtLeast(0))
                runCatching { windowManager.updateViewLayout(panel, panelParams) }
            }
        }
    }

    override fun onDestroy() {
        instance = null
        removePanel()
        if (::bubbleView.isInitialized) {
            runCatching { (currentBubbleWindowManager ?: windowManager).removeView(bubbleView) }
        }
        // ── Track 4 / Stage 1: Tear down the guidance pill HUD ───────────────
        if (::navigationHud.isInitialized) {
            navigationHud.destroy()
            Log.i("[NavigationEngine]", "NavigationHudOverlay.destroy() called from FloatingOverlayService.onDestroy()")
        }
        if (::elementMarker.isInitialized) {
            elementMarker.destroy()
            Log.i("[NavigationEngine]", "ElementMarkerOverlay.destroy() called from FloatingOverlayService.onDestroy()")
        }
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Overlay service destroyed")
    }

    // ── Foreground notification (required for a long-running Service on O+) ────

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "GuidanceApp", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps the GuidanceApp floating bubble active" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("GuidanceApp")
            .setContentText("Tap the floating icon to ask for help")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }

    private fun overlayWindowType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

    // ── Window flag helpers ────────────────────────────────────────────────────

    /**
     * Idle/drag mode: touches outside the bubble pass entirely to system UI.
     * NOT_FOCUSABLE prevents the IME from attaching.
     */
    private fun applyIdleFlags() {
        bubbleParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        runCatching { (currentBubbleWindowManager ?: windowManager).updateViewLayout(bubbleView, bubbleParams) }
        typingModeActive = false
    }

    /**
     * Typing mode: NOT_FOCUSABLE is removed so the IME can attach to the
     * EditText inside the panel. WATCH_OUTSIDE_TOUCH allows us to detect
     * taps outside the panel to dismiss it.
     */
    private fun applyTypingFlags() {
        bubbleParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        runCatching { (currentBubbleWindowManager ?: windowManager).updateViewLayout(bubbleView, bubbleParams) }
        typingModeActive = true
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    private fun addBubble() {
        val sizePx = dp(56)

        bubbleView = FrameLayout(this).apply {
            background = circleDrawable(Color.parseColor("#0D1F6E"))  // deep navy matches app logo gradient
            addView(
                android.widget.ImageView(this@FloatingOverlayService).apply {
                    setImageResource(R.drawable.ic_navvy_bubble)
                    scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        val wm = getBubbleWindowManager()
        val type = getBubbleWindowType()

        // Start in idle mode — fully pass-through to system UI
        bubbleParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 400
        }
        // [PERMANENT FIX] Do not remove this! Handle display cutouts cleanly on Android P+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bubbleParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        attachDragAndTapListener()
        try {
            wm.addView(bubbleView, bubbleParams)
            currentBubbleWindowManager = wm
            Log.i(TAG, "Floating bubble attached with window type: $type (isA11y=${type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY})")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach bubble via primary WM, falling back to Service WM: ${e.message}")
            bubbleParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            windowManager.addView(bubbleView, bubbleParams)
            currentBubbleWindowManager = windowManager
        }
    }

    /**
     * Single touch listener implementing drag-to-move and tap-to-open.
     *
     * Drag priority: once [totalMovement] exceeds [TAP_SLOP_PX] in ACTION_MOVE,
     * [dragLocked] is set. While locked the keyboard and panel are collapsed and
     * bubble continues moving smoothly. dragLocked resets on ACTION_DOWN.
     */
    private fun attachDragAndTapListener() {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var totalMovement = 0f
        var dragLocked = false

        bubbleView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = bubbleParams.x
                    initialY = bubbleParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    totalMovement = 0f
                    dragLocked = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    totalMovement += abs(dx) + abs(dy)

                    if (!dragLocked && totalMovement > TAP_SLOP_PX) {
                        // Drag threshold exceeded → drag takes priority over everything
                        dragLocked = true
                        // 1. Hide the keyboard (no-op if already hidden)
                        panelInputRef?.let { inputView ->
                            inputMethodManager.hideSoftInputFromWindow(
                                inputView.windowToken, 0
                            )
                        }
                        // 2. Collapse the control panel
                        if (panelView != null) {
                            removePanel()
                        }
                        // 3. Restore pass-through flags
                        if (typingModeActive) {
                            applyIdleFlags()
                        }
                    }

                    bubbleParams.x = initialX + dx.toInt()
                    bubbleParams.y = initialY + dy.toInt()
                    runCatching { (currentBubbleWindowManager ?: windowManager).updateViewLayout(bubbleView, bubbleParams) }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (totalMovement < TAP_SLOP_PX) {
                        toggleControlPanel()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ── Control panel ─────────────────────────────────────────────────────────

    private fun toggleControlPanel() {
        if (panelView != null) {
            // Second tap on bubble while panel is open → dismiss
            hidePanelAndRestoreIdle()
        } else {
            addControlPanel()
        }
    }

    private fun addControlPanel() {
        // Reset stop tap counter whenever the panel is freshly opened
        stopTapCount = 0

        val statusText = TextView(this).apply {
            setTextColor(Color.parseColor("#8A8A8A"))
            textSize = 12f
            setPadding(0, dp(8), 0, 0)
        }

        val input = EditText(this).apply {
            hint = "Enter a command (e.g., \"Call Mom\")"
            setHintTextColor(Color.parseColor("#8A8A8A"))
            setTextColor(Color.WHITE)
            minLines = 1
            maxLines = 4
            background = roundedRectDrawable(Color.parseColor("#2A2A2A"), dp(10).toFloat())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            // Switch to typing flags when the user taps the field
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) applyTypingFlags()
            }
        }
        panelInputRef = input

        val submitButton = Button(this).apply {
            text = "Submit"
            isAllCaps = false
            setTextColor(Color.parseColor("#001412"))
            background = roundedRectDrawable(Color.parseColor("#00D4C0"), dp(50).toFloat())
            setOnClickListener {
                val query = input.text?.toString()?.trim().orEmpty()

                // ── Step 1: client-side validation ────────────────────────────
                val validationResult = CommandValidator.validate(query)
                if (validationResult is ValidationResult.Invalid) {
                    statusText.text = validationResult.reason
                    return@setOnClickListener
                }

                // ── Step 2: send to AI ────────────────────────────────────────
                stopTapCount = 0  // reset on new submission
                handleSubmittedQuery(query, statusText)
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop"
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = roundedRectDrawable(Color.parseColor("#3A1414"), dp(50).toFloat())
            setOnClickListener { handleStop(statusText) }
        }

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
            addView(submitButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            })
            addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }


        // ── Developer Tools ────────────────────────────────────────────────────
        val devToolsLabel = TextView(this).apply {
            text = "DEV TOOLS"
            setTextColor(Color.parseColor("#505050"))
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            setPadding(0, dp(14), 0, dp(4))
        }

        val btnClearJson = Button(this).apply {
            text = "Clear JSONs"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            background = roundedRectDrawable(Color.parseColor("#2A2A2A"), dp(8).toFloat())
            setOnClickListener {
                // [PERMANENT FIX] Do not remove this! Include nav_graph.json in Clear JSONs dev action and notify web dashboard.
                val filesDir = applicationContext.filesDir
                val deleted = listOf("clean_page.json", "temp_tree.json", "nav_graph.json").map { name ->
                    val f = java.io.File(filesDir, name)
                    if (f.exists()) { f.delete(); name } else null
                }.filterNotNull()
                statusText.text = if (deleted.isEmpty()) "No JSON files found"
                                  else "Deleted: ${deleted.joinToString(", ")}"
                Log.i("[DevTools]", "Cleared JSONs: $deleted")

                if (deleted.contains("nav_graph.json")) {
                    DebugTelemetryClient.emit(
                        type = "CLEAR_GRAPH",
                        tag = "DevTools",
                        title = "JSON Files Cleared",
                        description = "Deleted ${deleted.joinToString(", ")}"
                    )
                }
            }
        }

        val btnClearGraph = Button(this).apply {
            text = "Clear Graph"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            background = roundedRectDrawable(Color.parseColor("#2A2A2A"), dp(8).toFloat())
            setOnClickListener {
                try {
                    // [PERMANENT FIX] Do not remove this! Universal Graph Clear with Dashboard Real-Time Reset.
                    // Deletes both nav_graph.db and exported nav_graph.json, and streams CLEAR_GRAPH event to web dashboard.
                    val db = NavGraphDatabase.getInstance(applicationContext)
                    db.close()
                    NavGraphDatabase::class.java.getDeclaredField("INSTANCE").apply {
                        isAccessible = true
                        set(null, null)
                    }
                    val dbFile = applicationContext.getDatabasePath("nav_graph.db")
                    val dbDeleted = dbFile.delete()

                    val jsonFile = java.io.File(applicationContext.filesDir, "nav_graph.json")
                    val jsonDeleted = if (jsonFile.exists()) jsonFile.delete() else false

                    statusText.text = if (dbDeleted || jsonDeleted) "Graph DB + JSON cleared" else "Graph DB not found"
                    Log.i("[DevTools]", "nav_graph.db deleted=$dbDeleted, nav_graph.json deleted=$jsonDeleted")

                    DebugTelemetryClient.emit(
                        type = "CLEAR_GRAPH",
                        tag = "DevTools",
                        title = "Graph Database Cleared",
                        description = "nav_graph.db and nav_graph.json wiped by user"
                    )
                } catch (e: Exception) {
                    statusText.text = "Error clearing graph: ${e.message}"
                    Log.e("[DevTools]", "Graph clear failed", e)
                }
            }
        }

        val btnResetState = Button(this).apply {
            text = "Reset State"
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.parseColor("#FFFFFF"))
            background = roundedRectDrawable(Color.parseColor("#2A2A2A"), dp(8).toFloat())
            setOnClickListener {
                NavigationStateMachine.stop()
                // Also clear first-run flag so it can be re-tested
                getSharedPreferences("nav_prefs", MODE_PRIVATE).edit()
                    .remove("home_marked").apply()
                statusText.text = "State + first-run flag reset"
                Log.i("[DevTools]", "NavigationStateMachine stopped + home_marked cleared")
            }
        }

        val devRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 0)
            val btnLp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(4)
            }
            addView(btnClearJson,  btnLp)
            addView(btnClearGraph, btnLp)
            addView(btnResetState, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundedRectDrawable(Color.parseColor("#1E1E1E"), dp(20).toFloat())
            addView(TextView(this@FloatingOverlayService).apply {
                text = "Floating Assistant"
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 0, 0, dp(12))
            })
            addView(input)
            addView(buttonRow)
            addView(statusText)
            addView(devToolsLabel)
            addView(devRow)
        }

        // Panel uses its own params; NOT_FOCUSABLE not set here so IME works
        val panelParams = WindowManager.LayoutParams(
            dp(280),
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleParams.x
            y = bubbleParams.y + dp(64)
        }
        // [PERMANENT FIX] Do not remove this! Handle display cutouts cleanly on Android P+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            panelParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        panelView = panel
        windowManager.addView(panel, panelParams)

        // Switch bubble to typing flags so IME can attach to the EditText
        applyTypingFlags()
    }

    private fun removePanel() {
        panelView?.let { runCatching { windowManager.removeView(it) } }
        panelView = null
        panelInputRef = null
    }

    /** Hides the panel, dismisses keyboard, and restores idle pass-through flags. */
    private fun hidePanelAndRestoreIdle() {
        panelInputRef?.let { inputView ->
            inputMethodManager.hideSoftInputFromWindow(inputView.windowToken, 0)
        }
        removePanel()
        applyIdleFlags()
    }

    // ── Query handling: Gemini → [Tier 1 local] → [Tier 2 cloud] → [Tier 3 Groq] ──

    /**
     * Full pipeline entry point for a validated user command.
     *
     * Phase 1  — Gemini parses the raw query into a structured intent
     *            {targetApp, destinationScreen, exactTask}.
     * Phase 2+ — Tier 1 / Tier 2 / Tier 3 path resolution wired here.
     *
     * Every major step logs to Logcat under the [PathFinder] tag so the
     * complete execution flow is traceable without a debugger.
     */
    private fun handleSubmittedQuery(query: String, statusText: TextView) {
        Log.i("[PathFinder]", "── New query ── \"$query\"")
        statusText.text = "Analysing…"

        // Clear any pending path from a previous query — a new query means the old
        // one is abandoned, so it should not be saved to Firestore on Stop.
        pendingGroqApp  = null
        pendingGroqTask = null
        pendingGroqPath = null
        pendingGroqIsPartial = false

        serviceScope.launch {
            // ── PHASE 1 STEP A: Intent classification (Proxy Server -> Gemini -> Local Fallback) ──
            Log.d("[PathFinder]", "Phase 1 — calling Command Parser")
            val parsed = withContext(Dispatchers.IO) {
                GeminiCommandParser.parse(query) { progressMsg ->
                    withContext(Dispatchers.Main) { statusText.text = progressMsg }
                }
            }

            // [PERMANENT FIX] Stream parsed intent to Live Dashboard
            DebugTelemetryClient.sendIntentParsed(
                query = query,
                targetApp = parsed.targetApp,
                screen = parsed.destinationScreen,
                task = parsed.exactTask
            )

            // GeminiCommandParser already emits the [PathFinder] log:
            //   "Parsed Intent -> App: X, Screen: Y, Task: Z"
            // Show structured result in the panel status area.
            statusText.text =
                "App: ${parsed.targetApp}\n" +
                "Screen: ${parsed.destinationScreen}\n" +
                "Task: ${parsed.exactTask}"

            Log.d("[PathFinder]", "Phase 1 complete — " +
                "targetApp=\"${parsed.targetApp}\" | " +
                "destinationScreen=\"${parsed.destinationScreen}\" | " +
                "exactTask=\"${parsed.exactTask}\"")

            val rawApp = parsed.targetApp.trim()
            val targetPkg = GraphStateMachine.getPackageNameForApp(rawApp)
                ?: if (rawApp.contains(".")) rawApp.lowercase() else GraphStateMachine.getPackageNameForApp(rawApp.lowercase()) ?: rawApp

            // ── PHASE 2 (Tier 1 — Local Graph Search) ─────────────────────────
            // [PERMANENT FIX] Do not remove this! Live foreground screen detection for Dijkstra start screen resolution.
            val liveScreenId = withContext(Dispatchers.Main) {
                val uiTree = UiTreeAccessibilityService.instance
                if (uiTree != null) {
                    val root = AppWindowResolver.getUnderlyingAppRoot(uiTree, packageName)
                    if (root != null) {
                        val livePkg = root.packageName?.toString()
                        val isTarget = livePkg != null && NavigationStateMachine.isTargetApp(livePkg, null)
                        val title = if (isTarget) {
                            ScreenTitleResolver.resolveTitle(root, parsed.targetApp, livePkg ?: targetPkg)
                        } else null
                        try { root.recycle() } catch (_: Exception) {}
                        if (!title.isNullOrBlank()) "$targetPkg::$title" else null
                    } else null
                } else null
            }

            val searchResult = withContext(Dispatchers.IO) {
                SearchPathEngine.findPath(
                    context = applicationContext,
                    targetApp = targetPkg,
                    destinationScreen = parsed.destinationScreen,
                    exactTask = parsed.exactTask,
                    currentScreenId = liveScreenId
                )
            }

            if (searchResult.found) {
                Log.i(TAG, "Local search matched: ${searchResult.pathString}")

                // [PERMANENT FIX] Do not remove this! Stream Tier 1 hit and all candidate paths to Dashboard
                val allPathsArr = JSONArray()
                searchResult.allDiscoveredPaths.forEach { p ->
                    val pObj = JSONObject().apply {
                        put("pathString", p.pathString)
                        val stepsArr = JSONArray()
                        p.steps.forEach { stepsArr.put(it) }
                        put("steps", stepsArr)
                        put("cost", p.cost)
                        put("startScreen", p.startScreen)
                        put("destinationScreen", p.destinationScreen)
                        put("targetElement", p.targetElement ?: "")
                        put("isShortest", p.isShortest)
                        put("score", p.score)
                        put("description", p.description)
                    }
                    allPathsArr.put(pObj)
                }
                val payloadObj = JSONObject().apply {
                    put("pathString", searchResult.pathString ?: "")
                    val stepsArr = JSONArray()
                    searchResult.steps.forEach { stepsArr.put(it) }
                    put("steps", stepsArr)
                    put("allPaths", allPathsArr)
                }

                DebugTelemetryClient.emit(
                    type = "INTENT_PARSED",
                    tag = "PathFinder",
                    title = "Tier 1 Local DB: Hit (${searchResult.allDiscoveredPaths.size} routes found)",
                    description = "Found path: ${searchResult.pathString}",
                    payload = payloadObj
                )

                // ── BUG-2: Collapse panel so HUD can be the sole guide UI ─────
                withContext(Dispatchers.Main) { hidePanelAndRestoreIdle() }

                // ── BUG-3 / Stage 2: Hand path to central state machine ────────
                val pathStr = searchResult.pathString.orEmpty()
                val steps   = pathStr.split("->").map { it.trim() }.filter { it.isNotEmpty() }
                Log.i("[NavigationEngine]", "Tier 1 → startNavigation(${steps.size} steps, pkg='$targetPkg')")
                NavigationStateMachine.startNavigation(steps, targetPkg, parsed.exactTask, source = "local")
                // HUD is now controlled by NavigationStateMachine; no direct hud calls here.

                return@launch
            }

            // ── PHASE 3 (Tier 2 — Firestore) ─────────────────────────────────
            Log.d("[PathFinder]", "Phase 3 — Tier 2 Firestore lookup (app=$targetPkg, task=${parsed.exactTask})")
            statusText.text = "Searching cloud database…"

            val cloudPath = withContext(Dispatchers.IO) {
                CloudPathDatabase.lookup(
                    targetApp = targetPkg,
                    exactTask = parsed.exactTask
                )
            }

            if (cloudPath.isNotEmpty()) {
                Log.i("[PathFinder]", "Tier 2 Firestore: Match Found → \"$cloudPath\"")

                // [PERMANENT FIX] Stream Tier 2 hit to Dashboard
                DebugTelemetryClient.emit(
                    type = "INTENT_PARSED",
                    tag = "PathFinder",
                    title = "Tier 2 Firestore: Hit",
                    description = "Found cloud path: $cloudPath",
                    payload = cloudPath
                )

                // ── BUG-2: Collapse panel so HUD can be the sole guide UI ─────
                withContext(Dispatchers.Main) { hidePanelAndRestoreIdle() }

                // ── BUG-3 / Stage 2: Hand path to central state machine ────────
                val cloudSteps = cloudPath.split("->").map { it.trim() }.filter { it.isNotEmpty() }
                Log.i("[NavigationEngine]", "Tier 2 → startNavigation(${cloudSteps.size} steps, pkg='$targetPkg')")
                NavigationStateMachine.startNavigation(cloudSteps, targetPkg, parsed.exactTask, source = "db")

                return@launch
            }

            Log.w("[PathFinder]", "Tier 2 Firestore: Miss — falling through to Tier 3 (Groq)")
            executeTier3Groq(targetPkg, parsed.exactTask, statusText)
        }
    }

    private fun triggerGroqReroute(app: String, task: String) {
        // [PERMANENT FIX] Stream Reroute trigger to Dashboard
        DebugTelemetryClient.emit(
            type = "REROUTE_TRIGGERED",
            tag = "PathRecovery",
            title = "Reroute Triggered",
            description = "User pressed Reroute. Prompting AI with fresh live screen elements...",
            packageName = app
        )

        // Run from UI thread
        serviceScope.launch(Dispatchers.Main) {
            // [PERMANENT FIX] Do not remove this! Immediate visual feedback on HUD during Reroute
            navigationHud.updateHud(
                stepHeader        = "REROUTING",
                actionInstruction = "Searching Cloud DB & AI...",
                breadcrumb        = null,
                contextText       = "Finding '$task'...",
                subTag            = "rerouting-ai",
                hudState          = NavigationHudOverlay.HudState.SEARCHING
            )

            // [PERMANENT FIX] Do not remove this! Check Tier 2 Cloud Database first before Groq AI during Reroute
            val canonicalPkg = GraphStateMachine.getPackageNameForApp(app)
                ?: if (app.contains(".")) app.lowercase() else GraphStateMachine.getPackageNameForApp(app.lowercase()) ?: app
            val cloudPath = withContext(Dispatchers.IO) {
                CloudPathDatabase.lookup(canonicalPkg, task)
            }
            if (cloudPath.isNotEmpty()) {
                Log.i("[NavigationEngine]", "Reroute matched Tier 2 Cloud DB: $cloudPath")
                val cloudSteps = cloudPath.split("->").map { it.trim() }.filter { it.isNotEmpty() }
                NavigationStateMachine.startNavigation(cloudSteps, canonicalPkg, task, isReroute = true, source = "db")
                return@launch
            }

            val dummyStatus = TextView(this@FloatingOverlayService)
            val error = executeTier3Groq(app, task, dummyStatus, pendingGroqPath)
            if (error != null) {
                // If it fails, log it and present retry option on HUD.
                Log.e("[PathFinder]", "Reroute failed: $error")
                navigationHud.updateHud(
                    stepHeader        = "AI SEARCH FAILED",
                    actionInstruction = error,
                    breadcrumb        = null,
                    contextText       = "Tap to try again",
                    subTag            = "reroute-failed",
                    hudState          = NavigationHudOverlay.HudState.OFF_TRACK,
                    onHelpRequested   = { triggerGroqReroute(app, task) }
                )
            }
        }
    }

    private suspend fun executeTier3Groq(targetApp: String, exactTask: String, statusText: TextView, failedPath: String? = null): String? {
        // ── PHASE 4 (Tier 3 — Groq) ──────────────────────────────────────
        Log.d("[PathFinder]", "Phase 4 — Tier 3 Groq fallback for app=$targetApp, task=$exactTask")
        withContext(Dispatchers.Main) { statusText.text = "Asking AI for path…" }

        // Capture the actual failure reason so it can be shown in the bubble
        var groqErrorReason: String? = null
        var isPartialPath = false

        val groqPath = withContext(Dispatchers.IO) {
            try {
                // 1. Force a live dump of the current screen to ensure we use fresh data
                var cleanPageContent = ""
                var liveScreenTitle: String? = null
                val uiTree = UiTreeAccessibilityService.instance
                val rootNode = if (uiTree != null) AppWindowResolver.getUnderlyingAppRoot(uiTree, this@FloatingOverlayService.packageName) else null
                
                if (rootNode != null) {
                    val pkg = rootNode.packageName?.toString() ?: targetApp
                    try {
                        liveScreenTitle = ScreenTitleResolver.resolveTitle(rootNode, targetApp, pkg)
                    } catch (e: Exception) {
                        Log.w("[PathFinder]", "resolveTitle error: ${e.message}")
                    }
                    // [PERMANENT FIX] Do not remove this! Use internal memory filesDir for all dump files
                    val tempFile = File(filesDir, "temp_reroute.json")
                    RawDumpWriter.onNavigation(this@FloatingOverlayService.serviceScope, tempFile, rootNode, pkg, "Reroute")
                    val snapshot = RawDumpWriter.getSnapshot(pkg)
                    if (snapshot != null) {
                        val cleanElements = CleanPageProcessor.extractSync(snapshot.nodes, pkg)
                        
                        // Construct the live JSON string
                        val output = org.json.JSONObject()
                        val meta = org.json.JSONObject()
                        meta.put("package_name", pkg)
                        meta.put("root_name", "Reroute")
                        meta.put("timestamp", System.currentTimeMillis())
                        meta.put("element_count", cleanElements.size)
                        output.put("meta", meta)
                        val arr = org.json.JSONArray()
                        cleanElements.forEach { arr.put(it) }
                        output.put("elements", arr)
                        
                        cleanPageContent = output.toString(2)

                        // Write to disk in background for debugging
                        val cleanFile = File(filesDir, CleanPageProcessor.CLEAN_FILE_NAME)
                        CleanPageProcessor.writeToFile(
                            scope = this@FloatingOverlayService.serviceScope,
                            outputFile = cleanFile,
                            elements = cleanElements,
                            packageName = pkg,
                            rootName = "Reroute"
                        )
                    }
                    rootNode.recycle()
                    Log.i("[PathFinder]", "Forced live screen dump for Reroute!")
                }
                if (liveScreenTitle.isNullOrBlank()) {
                    liveScreenTitle = NavigationStateMachine.activeScreenName.takeIf { it.isNotBlank() }
                }

                // Fallback to disk if live extraction failed
                if (cleanPageContent.isEmpty()) {
                    Log.w("[PathFinder]", "Live extraction failed, relying on old clean_page.json")
                    val cleanPageFile = File(filesDir, CleanPageProcessor.CLEAN_FILE_NAME)
                    cleanPageContent = if (cleanPageFile.exists()) cleanPageFile.readText() else ""
                }
                
                Log.d("[PathFinder]", "clean_page.json read: ${cleanPageContent.length} chars")

                // 2. Detect whether the user is already on the target app.
                val currentPackage = try {
                    org.json.JSONObject(cleanPageContent)
                        .optJSONObject("meta")
                        ?.optString("package_name", "") ?: ""
                } catch (_: Exception) { "" }
                val isOnTargetApp = currentPackage.isNotEmpty() &&
                        currentPackage.contains(targetApp.trim().lowercase(), ignoreCase = true)
                Log.d("[PathFinder]", "currentPackage=$currentPackage, targetApp=$targetApp, isOnTargetApp=$isOnTargetApp")
                
                isPartialPath = (failedPath != null || isOnTargetApp)

                // 3. Gather device info
                val deviceInfo = DeviceInfoGatherer.gather(this@FloatingOverlayService)

                // [PERMANENT FIX] Do not remove this! Full hierarchical explored tree Reroute payload.
                // Sends the actual nested screen hierarchy with real elements and outgoing branches explored till now,
                // ensuring the AI knows what is inside what (e.g. Dark mode inside Display & brightness) rather than just a flat list.
                val navGraphContext = try {
                    val db = NavGraphDatabase.getInstance(this@FloatingOverlayService)
                    val canonicalPkg = GraphStateMachine.getPackageNameForApp(targetApp) ?: targetApp
                    val connectedScreens = db.getConnectedScreens(canonicalPkg)
                    val connectedTransitions = db.getConnectedTransitions(canonicalPkg)

                    if (connectedScreens.isNotEmpty()) {
                        val gObj = org.json.JSONObject()
                        gObj.put("app", targetApp)
                        gObj.put("package", canonicalPkg)
                        val screensArr = org.json.JSONArray()

                        connectedScreens.forEach { s ->
                            val sObj = org.json.JSONObject()
                            sObj.put("screen", s.screenTitle)

                            // Extract real elements inside this screen
                            val elemArr = org.json.JSONArray()
                            try {
                                val rawElems = org.json.JSONArray(s.elementsJson)
                                for (i in 0 until rawElems.length()) {
                                    val name = rawElems.optJSONObject(i)?.optString("name", "")?.trim() ?: ""
                                    if (name.isNotEmpty()) elemArr.put(name)
                                }
                            } catch (_: Exception) {}
                            sObj.put("elements", elemArr)

                            // Extract outgoing transitions from this screen
                            val branchArr = org.json.JSONArray()
                            connectedTransitions.filter { it.fromScreenId == s.id && it.actionType == "CLICK" }.forEach { t ->
                                val toTitle = connectedScreens.find { it.id == t.toScreenId }?.screenTitle ?: t.toScreenId.substringAfterLast("::")
                                val bObj = org.json.JSONObject()
                                bObj.put("action", t.actionLabel)
                                bObj.put("to_screen", toTitle)
                                branchArr.put(bObj)
                            }
                            sObj.put("branches", branchArr)

                            screensArr.put(sObj)
                        }
                        gObj.put("explored_tree", screensArr)
                        gObj
                    } else null
                } catch (e: Exception) {
                    Log.w("[PathFinder]", "Failed to extract hierarchical graph context: ${e.message}")
                    null
                }

                // 4. Build prompt and call Groq
                val userPrompt = PromptBuilder.buildGeminiDrivenPrompt(
                    targetApp,
                    exactTask,
                    cleanPageContent,
                    deviceInfo,
                    isOnTargetApp,
                    failedPath,
                    liveScreenTitle,
                    navGraphContext
                )
                Log.d("[PathFinder]", "Sending to Groq proxy… isOnTargetApp=$isOnTargetApp")
                val rawResponse = GroqProxyClient().sendDirectRequest(
                    PromptBuilder.SYSTEM_PROMPT,
                    userPrompt
                )
                Log.d("[PathFinder]", "Groq raw response: $rawResponse")

                // 5. Parse the JSON path response
                val navPath = GroqResponseParser.parse(rawResponse)
                if (navPath.isValid) {
                    navPath.toPathString()
                } else {
                    groqErrorReason = "AI returned no steps: ${navPath.errorMessage}"
                    Log.w("[PathFinder]", "Groq parse invalid: ${navPath.errorMessage}")
                    null
                }

            } catch (e: java.net.SocketTimeoutException) {
                groqErrorReason = "Network timeout — check internet connection"
                Log.e("[PathFinder]", "Tier 3 Groq timeout: ${e.message}", e)
                null
            } catch (e: java.net.UnknownHostException) {
                groqErrorReason = "No internet — could not reach AI server"
                Log.e("[PathFinder]", "Tier 3 Groq no host: ${e.message}", e)
                null
            } catch (e: Exception) {
                groqErrorReason = e.message ?: "Unknown error"
                Log.e("[PathFinder]", "Tier 3 Groq failed: ${e.message}", e)
                null
            }
        }

        if (!groqPath.isNullOrEmpty()) {
            Log.i("[PathFinder]", "Tier 3 Groq: Generated path → \"$groqPath\"")

            val sanitisedGroqPath = groqPath.split("->").map { it.trim() }.filter { it.isNotEmpty() }
                .filter { step ->
                    val norm = step.trim().lowercase()
                    norm != "back" && norm != "go back" && norm != "return" &&
                    norm != "navigate back" && norm != "press back" && norm != "home"
                }
                .joinToString(" -> ")
            pendingGroqApp  = targetApp
            pendingGroqTask = exactTask
            pendingGroqPath = sanitisedGroqPath
            // [PERMANENT FIX] Do not remove this! Preserve isPartialPath flag so handleStop knows not to upload partial paths to Firestore
            pendingGroqIsPartial = isPartialPath

            withContext(Dispatchers.Main) { hidePanelAndRestoreIdle() }

            val groqSteps = groqPath.split("->").map { it.trim() }.filter { it.isNotEmpty() }
                .filter { step ->
                    val norm = step.trim().lowercase()
                    norm != "back" && norm != "go back" && norm != "return" &&
                    norm != "navigate back" && norm != "press back" &&
                    norm != "home"
                }
            if (groqSteps.isEmpty()) {
                Log.w("[PathFinder]", "Groq path was empty after sanitization")
                withContext(Dispatchers.Main) {
                    statusText.text = "❌ AI returned no usable steps"
                    navigationHud.hide()
                }
                return "Empty path after sanitization"
            }
            Log.i("[NavigationEngine]", "Tier 3 → startNavigation(${groqSteps.size} steps, pkg='$targetApp')")
            NavigationStateMachine.startNavigation(groqSteps, targetApp, exactTask, isReroute = isPartialPath, source = "ai")
            return null
        } else {
            Log.w("[PathFinder]", "Tier 3 Groq: Failed — reason: $groqErrorReason")
            withContext(Dispatchers.Main) {
                statusText.text = buildString {
                    append("❌ AI path failed\n")
                    append("App: $targetApp\n")
                    append("Task: $exactTask\n\n")
                    if (!groqErrorReason.isNullOrEmpty()) append("Reason: $groqErrorReason")
                    else append("Unknown error — check Logcat ([PathFinder] tag)")
                }
                navigationHud.hide()
            }
            Log.i("[NavigationEngine]", "All tiers failed — HUD hidden")
            return groqErrorReason ?: "Unknown error"
        }
    }

    // ── Stop button — multi-tap logic ──────────────────────────────────────────

    /**
     * Tap 1: Stop the current pathfinding/highlighting state machine.
     * Tap 2 (consecutive, while panel still open): Show an AlertDialog asking
     *         whether to exit the app completely.
     *           Yes → stop service + remove views + exitProcess(0)
     *           No  → dismiss; counter reset to 0.
     */
    private fun handleStop(statusText: TextView) {
        when (stopTapCount) {
            0 -> {
                // ── Tap 1: stop navigation ─────────────────────────────────
                NavigationStateMachine.stop()
                stopTapCount = 1
                Log.i(TAG, "Stop tap 1 — navigation stopped")

                // ── Stage 1: hide the guidance HUD on Stop ────────────────
                navigationHud.hide()
                Log.i("[NavigationEngine]", "HUD hidden — user pressed Stop")

                val app  = pendingGroqApp
                val task = pendingGroqTask
                val path = pendingGroqPath
                val isPartial = pendingGroqIsPartial

                if (!app.isNullOrEmpty() && !task.isNullOrEmpty() && !path.isNullOrEmpty()) {
                    if (isPartial) {
                        // [PERMANENT FIX] Do not remove this! Never upload partial paths (e.g. from Reroutes or mid-app) to Cloud Firestore per Rule 5.1
                        Log.i("[PathFinder]", "Path was partial/reroute ($app: $path) — skipping cloud save dialog per Rule 5.1")
                        statusText.text = "Stopped (Reroute not saved to cloud)"
                        pendingGroqApp  = null
                        pendingGroqTask = null
                        pendingGroqPath = null
                        pendingGroqIsPartial = false
                    } else {
                        // Groq full path was shown — ask user if it worked before saving
                        showSavePathDialog(statusText, app, task, path)
                    }
                } else {
                    statusText.text = "Stopped"
                }
            }
            else -> {
                // ── Tap 2+: prompt for full exit ───────────────────────────
                stopTapCount = 0
                Log.i(TAG, "Stop tap 2 — showing exit dialog")
                showExitDialog()
            }
        }
    }

    /**
     * Shows a dialog asking the user whether the AI-generated path worked correctly.
     *
     * Yes → saves [path] to Firestore under [app]/[task] so future lookups hit Tier 2.
     * No  → discards the pending path silently (no Firestore write).
     */
    private fun showSavePathDialog(
        statusText: TextView,
        app: String,
        task: String,
        path: String
    ) {
        // Clear pending immediately — dialog handles the decision from here
        pendingGroqApp  = null
        pendingGroqTask = null
        pendingGroqPath = null
        pendingGroqIsPartial = false

        try {
            val dialogContext = ContextThemeWrapper(
                applicationContext,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            )
            AlertDialog.Builder(dialogContext)
                .setTitle("Did it work?")
                .setMessage("Was the AI path correct?\nSave it so it's remembered next time?")
                .setPositiveButton("Yes, Save") { dialog, _ ->
                    dialog.dismiss()
                    statusText.text = "Saving path to cloud…"
                    Log.i("[PathFinder]", "User confirmed path correct — saving to Firestore: app=$app, task=$task")
                    serviceScope.launch {
                        try {
                            kotlinx.coroutines.withTimeout(3000L) {
                                withContext(Dispatchers.IO) {
                                    val knownPkg = if (app.contains('.')) app else GraphStateMachine.getPackageNameForApp(app)
                                    // [PERMANENT FIX] Do not remove this! Pass dual-key targetPackage so Firestore saves under both label and package name
                                    CloudPathDatabase.addEntry(
                                        targetApp = app,
                                        exactTask = task,
                                        path      = path,
                                        targetPackage = knownPkg
                                    )
                                }
                            }
                            Log.i("[PathFinder]", "Tier 3 Groq: Path stored to Firestore ✓ (user confirmed)")
                            statusText.text = "✓ Saved! This path will be remembered next time."
                        } catch (e: Exception) {
                            Log.w("[PathFinder]", "Cloud save timed out or failed (queued locally): ${e.message}")
                            statusText.text = "✓ Saved locally! (Will sync when online)"
                        }
                    }
                }
                .setNegativeButton("No, Discard") { dialog, _ ->
                    dialog.dismiss()
                    Log.i("[PathFinder]", "User discarded Groq path — not saving to Firestore")
                    statusText.text = "Stopped (path discarded)"
                }
                .setCancelable(false)
                .create()
                .apply { window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
                .show()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show save dialog: ${e.message}", e)
            statusText.text = "Stopped"
        }
    }

    /**
     * Show a native [AlertDialog] from a [Service] context.
     *
     * We use [ContextThemeWrapper] with [android.R.style.Theme_DeviceDefault_Light_Dialog_Alert]
     * because a raw [Service] context has no window token and therefore cannot
     * show a Dialog directly. This is the standard workaround for overlay services.
     *
     * Note: on some MIUI / HyperOS devices background dialogs are restricted.
     * If users report a crash on those devices we can switch to a custom
     * overlay-View dialog using TYPE_APPLICATION_OVERLAY.
     */
    private fun showExitDialog() {
        try {
            val dialogContext = ContextThemeWrapper(
                applicationContext,
                android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            )
            val dialog = AlertDialog.Builder(dialogContext)
                .setTitle("Exit App")
                .setMessage("Would you like to exit the app completely?")
                .setPositiveButton("Yes") { dlg, _ ->
                    dlg.dismiss()
                    Log.i(TAG, "Exit confirmed by user — shutting down")
                    NavigationStateMachine.stop()
                    // Remove the bubble view before stopping the service so
                    // there's no orphaned window after the process exits.
                    if (::bubbleView.isInitialized) {
                        runCatching { (currentBubbleWindowManager ?: windowManager).removeView(bubbleView) }
                    }
                    removePanel()
                    FloatingOverlayService.stop(applicationContext)
                    exitProcess(0)
                }
                .setNegativeButton("No") { dlg, _ ->
                    dlg.dismiss()
                    stopTapCount = 0
                    Log.i(TAG, "Exit cancelled by user")
                }
                .create()

            // TYPE_APPLICATION_OVERLAY is required for dialogs from a Service.
            dialog.window?.setType(overlayWindowType())
            dialog.show()
        } catch (e: Exception) {
            // Defensive: if the dialog fails (restricted OEM), log and do nothing.
            Log.e(TAG, "showExitDialog failed — ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    // ── Drawable helpers (avoids adding new drawable XML resources) ────────────

    private fun circleDrawable(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundedRectDrawable(color: Int, radiusPx: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radiusPx
        setColor(color)
    }

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics
        ).toInt()
}
