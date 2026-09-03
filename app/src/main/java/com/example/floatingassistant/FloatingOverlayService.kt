package com.example.floatingassistant

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
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

    // ── Control panel (shown on tap, dismissed on submit / drag / outside tap) ─
    private var panelView: View? = null
    private var panelInputRef: EditText? = null     // held for keyboard hide

    // ── Stop button tap counter ───────────────────────────────────────────────
    private var stopTapCount = 0

    // ── Pending Groq path — saved to Firestore only when user confirms task done (Stop tap 1) ──
    private var pendingGroqApp:  String? = null
    private var pendingGroqTask: String? = null
    private var pendingGroqPath: String? = null

    // ── Whether we are currently in typing mode ───────────────────────────────
    private var typingModeActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()
        serviceScope.launch {
            // Firestore security rules require an authenticated (anonymous) user —
            // sign in once up front so the first query submission isn't slowed down by it.
            CloudPathDatabase.ensureSignedIn()
        }



        Log.i(TAG, "Overlay service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        removePanel()
        if (::bubbleView.isInitialized) {
            runCatching { windowManager.removeView(bubbleView) }
        }
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Overlay service destroyed")
    }

    // ── Foreground notification (required for a long-running Service on O+) ────

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Floating Assistant", NotificationManager.IMPORTANCE_MIN
            ).apply { description = "Keeps the floating assistant bubble active" }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Floating Assistant")
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
        runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
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
        runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
        typingModeActive = true
    }

    // ── Bubble ────────────────────────────────────────────────────────────────

    private fun addBubble() {
        val sizePx = dp(56)

        bubbleView = FrameLayout(this).apply {
            background = circleDrawable(Color.parseColor("#00D4C0"))
            addView(
                TextView(this@FloatingOverlayService).apply {
                    text = "⚡"
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setTextColor(Color.parseColor("#001412"))
                },
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        // Start in idle mode — fully pass-through to system UI
        bubbleParams = WindowManager.LayoutParams(
            sizePx, sizePx,
            overlayWindowType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 200
        }

        attachDragAndTapListener()
        windowManager.addView(bubbleView, bubbleParams)
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
                    runCatching { windowManager.updateViewLayout(bubbleView, bubbleParams) }
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

        serviceScope.launch {
            if (BuildConfig.GEMINI_API_KEY.isBlank() ||
                BuildConfig.GEMINI_API_KEY == "YOUR_GEMINI_API_KEY_HERE") {
                statusText.text =
                    "Gemini API key is not set.\nAdd GEMINI_API_KEY to local.properties."
                Log.e("[PathFinder]", "Gemini API key missing — aborting")
                return@launch
            }

            // ── PHASE 1 STEP A: Gemini intent classification ──────────────────
            Log.d("[PathFinder]", "Phase 1 — calling GeminiCommandParser")
            val parsed = withContext(Dispatchers.IO) {
                GeminiCommandParser.parse(query) { progressMsg ->
                    withContext(Dispatchers.Main) { statusText.text = progressMsg }
                }
            }

            if (parsed == null) {
                Log.w("[PathFinder]", "Phase 1 — GeminiCommandParser returned null (server busy or bad format)")
                statusText.text = "Server is busy — please try again in a moment."
                return@launch
            }

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

            // ── PHASE 2 (Tier 1 — Local Graph BFS) — wired in Phase 2 ─────────
            // val localPath = SearchPathEngine.find(
            //     fromScreenId = GraphStateMachine.currentScreenId(parsed.targetApp),
            //     toScreenTitle = parsed.destinationScreen
            // )
            // if (localPath != null) { … dispatch … return@launch }

            // ── PHASE 3 (Tier 2 — Firestore) ─────────────────────────────────
            Log.d("[PathFinder]", "Phase 3 — Tier 2 Firestore lookup (app=${parsed.targetApp}, task=${parsed.exactTask})")
            statusText.text = "Searching cloud database…"

            val cloudPath = withContext(Dispatchers.IO) {
                CloudPathDatabase.lookup(
                    targetApp = parsed.targetApp,
                    exactTask = parsed.exactTask
                )
            }

            if (cloudPath.isNotEmpty()) {
                Log.i("[PathFinder]", "Tier 2 Firestore: Match Found → \"$cloudPath\"")
                statusText.text =
                    "✓ Path found:\n" +
                    "App: ${parsed.targetApp}\n" +
                    "Task: ${parsed.exactTask}\n\n" +
                    cloudPath
                // TODO Phase 4: dispatch cloudPath to NavigationStateMachine
                return@launch
            }

            Log.w("[PathFinder]", "Tier 2 Firestore: Miss — falling through to Tier 3 (Groq)")

            // ── PHASE 4 (Tier 3 — Groq) ──────────────────────────────────────
            Log.d("[PathFinder]", "Phase 4 — Tier 3 Groq fallback for app=${parsed.targetApp}, task=${parsed.exactTask}")
            statusText.text = "Asking AI for path…"

            // Capture the actual failure reason so it can be shown in the bubble
            var groqErrorReason: String? = null

            val groqPath = withContext(Dispatchers.IO) {
                try {
                    // 1. Read live screen state from clean_page.json
                    val cleanPageFile = File(
                        getExternalFilesDir(null) ?: cacheDir,
                        CleanPageProcessor.CLEAN_FILE_NAME
                    )
                    val cleanPageContent = if (cleanPageFile.exists()) cleanPageFile.readText() else ""
                    Log.d("[PathFinder]", "clean_page.json read: ${cleanPageContent.length} chars")

                    // 2. Gather device info
                    val deviceInfo = DeviceInfoGatherer.gather(this@FloatingOverlayService)

                    // 3. Build prompt and call Groq
                    val userPrompt = PromptBuilder.buildGeminiDrivenPrompt(
                        parsed.targetApp,
                        parsed.exactTask,
                        cleanPageContent,
                        deviceInfo
                    )
                    Log.d("[PathFinder]", "Sending to Groq proxy…")
                    val rawResponse = GroqProxyClient().sendDirectRequest(
                        PromptBuilder.SYSTEM_PROMPT,
                        userPrompt
                    )
                    Log.d("[PathFinder]", "Groq raw response: $rawResponse")

                    // 4. Parse the JSON path response
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

                // Hold the path — save to Firestore only after the user confirms the task
                // is done correctly by pressing Stop (tap 1). See handleStop().
                pendingGroqApp  = parsed.targetApp
                pendingGroqTask = parsed.exactTask
                pendingGroqPath = groqPath

                statusText.text =
                    "✓ Path found (AI):\n" +
                    "App: ${parsed.targetApp}\n" +
                    "Task: ${parsed.exactTask}\n\n" +
                    groqPath

            } else {
                Log.w("[PathFinder]", "Tier 3 Groq: Failed — reason: $groqErrorReason")
                statusText.text = buildString {
                    append("❌ AI path failed\n")
                    append("App: ${parsed.targetApp}\n")
                    append("Task: ${parsed.exactTask}\n\n")
                    if (!groqErrorReason.isNullOrEmpty()) {
                        append("Reason: $groqErrorReason")
                    } else {
                        append("Unknown error — check Logcat ([PathFinder] tag)")
                    }
                }
            }
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

                val app  = pendingGroqApp
                val task = pendingGroqTask
                val path = pendingGroqPath

                if (!app.isNullOrEmpty() && !task.isNullOrEmpty() && !path.isNullOrEmpty()) {
                    // Groq path was shown — ask user if it worked before saving
                    showSavePathDialog(statusText, app, task, path)
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
                        withContext(Dispatchers.IO) {
                            CloudPathDatabase.addEntry(
                                targetApp = app,
                                exactTask = task,
                                path      = path
                            )
                        }
                        Log.i("[PathFinder]", "Tier 3 Groq: Path stored to Firestore ✓ (user confirmed)")
                        statusText.text = "✓ Saved! This path will be remembered next time."
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
                        runCatching { windowManager.removeView(bubbleView) }
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