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
import com.example.floatingassistant.intent.IntentClassifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.system.exitProcess

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

    // ── Whether we are currently in typing mode ───────────────────────────────
    private var typingModeActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        inputMethodManager = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        startForeground(NOTIFICATION_ID, buildNotification())
        addBubble()

        // Warm up the on-device intent classifier now (loads the ONNX model +
        // precomputes example embeddings) so the first user query isn't slow.
        // Runs off the main thread; failures are logged and handled inside
        // IntentClassifier itself (classify() just reports UNKNOWN_INTENT).
        serviceScope.launch(Dispatchers.IO) {
            IntentClassifier.ensureInitialized(applicationContext)
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
            hint = "e.g. I want to change whatsapp dp"
            setHintTextColor(Color.parseColor("#8A8A8A"))
            setTextColor(Color.WHITE)
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
                if (query.isEmpty()) {
                    statusText.text = "Type a request first"
                    return@setOnClickListener
                }
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

    // ── Query handling: intent classifier → DB lookup → state machine ────────

    /**
     * Entry point for the Submit button.
     *
     * 1. Run the on-device MiniLM intent classifier on [query] first. If it
     *    confidently recognizes one of the fixed IntentExamples intents
     *    (dark mode, volume, music, etc.), handle it directly.
     * 2. Otherwise (UNKNOWN_INTENT — including when the model failed to load,
     *    or confidence was too low) fall back to the existing PathDatabase
     *    keyword lookup + NavigationStateMachine flow, unchanged from before
     *    this feature was added.
     */
    private fun handleSubmittedQuery(query: String, statusText: TextView) {
        statusText.text = "Thinking…"
        serviceScope.launch {
            val classification = withContext(Dispatchers.Default) {
                IntentClassifier.classify(this@FloatingOverlayService, query)
            }
            Log.i(
                TAG,
                "Intent classification for \"$query\" → ${classification.intent} " +
                    "(confidence=${"%.2f".format(classification.confidence)})"
            )

            if (classification.intent != IntentClassifier.UNKNOWN_INTENT) {
                statusText.text =
                    "Intent: ${classification.intent} (${(classification.confidence * 100).toInt()}%)"
                executeRecognizedIntent(classification.intent)
                hidePanelAndRestoreIdle()
                return@launch
            }

            // No confident intent match — fall back to device-specific
            // navigation-path lookup (e.g. "change my WhatsApp DP").
            statusText.text = "Searching…"
            val path = withContext(Dispatchers.IO) {
                PathDatabase.lookup(this@FloatingOverlayService, query)
            }

            if (path.isEmpty()) {
                statusText.text = "Path not found for this request/device"
                Log.w(TAG, "No path resolved for query=\"$query\"")
                return@launch
            }

            NavigationStateMachine.start(path)
            statusText.text = "Guiding: $path"
            Log.i(TAG, "Guide started for query=\"$query\" → $path")
            hidePanelAndRestoreIdle()
        }
    }

    /**
     * Runs the app action tied to a recognized intent, if one exists yet.
     * None of these intents have a real app action wired up in this project
     * today, so this is a clearly-marked extension point — add a `when`
     * branch here as each action gets implemented, without touching the
     * classifier or the fallback path-lookup flow above.
     */
    private fun executeRecognizedIntent(intent: String) {
        when (intent) {
            "TURN_ON_DARK_MODE" -> Log.i(TAG, "TODO: wire up dark-mode action for $intent")
            "TURN_ON_LIGHT_MODE" -> Log.i(TAG, "TODO: wire up light-mode action for $intent")
            "OPEN_SETTINGS" -> Log.i(TAG, "TODO: wire up open-settings action for $intent")
            "CLOSE_SETTINGS" -> Log.i(TAG, "TODO: wire up close-settings action for $intent")
            "INCREASE_VOLUME" -> Log.i(TAG, "TODO: wire up volume-up action for $intent")
            "DECREASE_VOLUME" -> Log.i(TAG, "TODO: wire up volume-down action for $intent")
            "PLAY_MUSIC" -> Log.i(TAG, "TODO: wire up play-music action for $intent")
            "PAUSE_MUSIC" -> Log.i(TAG, "TODO: wire up pause-music action for $intent")
            "STOP_MUSIC" -> Log.i(TAG, "TODO: wire up stop-music action for $intent")

            // ── Mobile Settings ─────────────────────────────────────────
            "TURN_ON_WIFI" -> Log.i(TAG, "TODO: wire up wifi-on action for $intent")
            "TURN_OFF_WIFI" -> Log.i(TAG, "TODO: wire up wifi-off action for $intent")
            "TURN_ON_BLUETOOTH" -> Log.i(TAG, "TODO: wire up bluetooth-on action for $intent")
            "TURN_OFF_BLUETOOTH" -> Log.i(TAG, "TODO: wire up bluetooth-off action for $intent")
            "TURN_ON_AIRPLANE_MODE" -> Log.i(TAG, "TODO: wire up airplane-mode-on action for $intent")
            "TURN_OFF_AIRPLANE_MODE" -> Log.i(TAG, "TODO: wire up airplane-mode-off action for $intent")
            "INCREASE_BRIGHTNESS" -> Log.i(TAG, "TODO: wire up brightness-up action for $intent")
            "DECREASE_BRIGHTNESS" -> Log.i(TAG, "TODO: wire up brightness-down action for $intent")
            "TURN_ON_DO_NOT_DISTURB" -> Log.i(TAG, "TODO: wire up DND-on action for $intent")
            "TURN_OFF_DO_NOT_DISTURB" -> Log.i(TAG, "TODO: wire up DND-off action for $intent")
            "TURN_ON_FLASHLIGHT" -> Log.i(TAG, "TODO: wire up flashlight-on action for $intent")
            "TURN_OFF_FLASHLIGHT" -> Log.i(TAG, "TODO: wire up flashlight-off action for $intent")
            "CHECK_BATTERY_STATUS" -> Log.i(TAG, "TODO: wire up battery-status action for $intent")
            "CHECK_STORAGE" -> Log.i(TAG, "TODO: wire up storage-check action for $intent")
            "LOCK_SCREEN" -> Log.i(TAG, "TODO: wire up lock-screen action for $intent")
            "CHANGE_WALLPAPER" -> Log.i(TAG, "TODO: wire up change-wallpaper action for $intent")

            // ── WhatsApp ─────────────────────────────────────────────────
            "OPEN_WHATSAPP" -> Log.i(TAG, "TODO: wire up open-whatsapp action for $intent")
            "CHANGE_WHATSAPP_PROFILE_PICTURE" -> Log.i(TAG, "TODO: wire up whatsapp-dp-change action for $intent")
            "SEND_WHATSAPP_MESSAGE" -> Log.i(TAG, "TODO: wire up send-whatsapp-message action for $intent")
            "MAKE_WHATSAPP_CALL" -> Log.i(TAG, "TODO: wire up whatsapp-voice-call action for $intent")
            "MAKE_WHATSAPP_VIDEO_CALL" -> Log.i(TAG, "TODO: wire up whatsapp-video-call action for $intent")
            "MUTE_WHATSAPP_CHAT" -> Log.i(TAG, "TODO: wire up mute-whatsapp-chat action for $intent")
            "UNMUTE_WHATSAPP_CHAT" -> Log.i(TAG, "TODO: wire up unmute-whatsapp-chat action for $intent")
            "ARCHIVE_WHATSAPP_CHAT" -> Log.i(TAG, "TODO: wire up archive-whatsapp-chat action for $intent")
            "DELETE_WHATSAPP_CHAT" -> Log.i(TAG, "TODO: wire up delete-whatsapp-chat action for $intent")
            "BLOCK_WHATSAPP_CONTACT" -> Log.i(TAG, "TODO: wire up block-whatsapp-contact action for $intent")
            "OPEN_WHATSAPP_STATUS" -> Log.i(TAG, "TODO: wire up open-whatsapp-status action for $intent")
            "CLEAR_WHATSAPP_CHAT_HISTORY" -> Log.i(TAG, "TODO: wire up clear-whatsapp-history action for $intent")

            // ── YouTube ──────────────────────────────────────────────────
            "SEARCH_YOUTUBE_VIDEO" -> Log.i(TAG, "TODO: wire up youtube-search action for $intent")
            "PLAY_YOUTUBE_VIDEO" -> Log.i(TAG, "TODO: wire up youtube-play action for $intent")
            "PAUSE_YOUTUBE_VIDEO" -> Log.i(TAG, "TODO: wire up youtube-pause action for $intent")
            "SUBSCRIBE_YOUTUBE_CHANNEL" -> Log.i(TAG, "TODO: wire up youtube-subscribe action for $intent")
            "UNSUBSCRIBE_YOUTUBE_CHANNEL" -> Log.i(TAG, "TODO: wire up youtube-unsubscribe action for $intent")
            "LIKE_YOUTUBE_VIDEO" -> Log.i(TAG, "TODO: wire up youtube-like action for $intent")
            "DISLIKE_YOUTUBE_VIDEO" -> Log.i(TAG, "TODO: wire up youtube-dislike action for $intent")
            "OPEN_YOUTUBE_HISTORY" -> Log.i(TAG, "TODO: wire up youtube-history-open action for $intent")
            "CLEAR_YOUTUBE_HISTORY" -> Log.i(TAG, "TODO: wire up youtube-history-clear action for $intent")
            "ADD_TO_WATCH_LATER" -> Log.i(TAG, "TODO: wire up youtube-watch-later action for $intent")
            "TURN_ON_YOUTUBE_CAPTIONS" -> Log.i(TAG, "TODO: wire up youtube-captions-on action for $intent")
            "TURN_OFF_YOUTUBE_CAPTIONS" -> Log.i(TAG, "TODO: wire up youtube-captions-off action for $intent")
            "CHANGE_YOUTUBE_PLAYBACK_SPEED" -> Log.i(TAG, "TODO: wire up youtube-playback-speed action for $intent")

            // ── Email ────────────────────────────────────────────────────
            "COMPOSE_EMAIL" -> Log.i(TAG, "TODO: wire up compose-email action for $intent")
            "SEND_EMAIL" -> Log.i(TAG, "TODO: wire up send-email action for $intent")
            "DELETE_EMAIL" -> Log.i(TAG, "TODO: wire up delete-email action for $intent")
            "ARCHIVE_EMAIL" -> Log.i(TAG, "TODO: wire up archive-email action for $intent")
            "MARK_EMAIL_AS_READ" -> Log.i(TAG, "TODO: wire up mark-email-read action for $intent")
            "MARK_EMAIL_AS_UNREAD" -> Log.i(TAG, "TODO: wire up mark-email-unread action for $intent")
            "OPEN_EMAIL_INBOX" -> Log.i(TAG, "TODO: wire up open-inbox action for $intent")
            "OPEN_SENT_EMAILS" -> Log.i(TAG, "TODO: wire up open-sent-emails action for $intent")
            "OPEN_EMAIL_DRAFTS" -> Log.i(TAG, "TODO: wire up open-drafts action for $intent")
            "SEARCH_EMAIL" -> Log.i(TAG, "TODO: wire up search-email action for $intent")
            "REPLY_TO_EMAIL" -> Log.i(TAG, "TODO: wire up reply-email action for $intent")
            "FORWARD_EMAIL" -> Log.i(TAG, "TODO: wire up forward-email action for $intent")
            "STAR_EMAIL" -> Log.i(TAG, "TODO: wire up star-email action for $intent")

            else -> Log.w(TAG, "Recognized intent \"$intent\" has no registered action yet")
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
                statusText.text = "Stopped"
                stopTapCount = 1
                Log.i(TAG, "Stop tap 1 — navigation stopped")
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