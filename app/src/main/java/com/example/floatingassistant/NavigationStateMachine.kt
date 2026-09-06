package com.example.floatingassistant

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * NavigationStateMachine — Track 4 / Stage 2
 *
 * The central, singleton state tracker for an active navigation session.
 * It receives the resolved path from [FloatingOverlayService], then listens
 * to screen-change events forwarded from [UiTreeAccessibilityService] to
 * determine whether the user is on-track, has diverged, or is in the wrong app.
 *
 * ── State Variables ──────────────────────────────────────────────────────────
 *   targetPackage   — package name (or short name) the path belongs to
 *   pathSteps       — ordered list of step names  e.g. ["Home","Settings","System","About phone"]
 *   currentIndex    — index of the step currently being completed  (0-based)
 *   prevStep        — step just behind currentIndex (null if at index 0)
 *   currStep        — step the user must currently navigate to
 *   nextStep        — step after currStep (null if currStep is the last)
 *   lastCorrectStep — most recently validated on-path screen
 *   isNavigating    — true while a session is active
 *
 * ── Alignment Decision Tree (onScreenChanged) ────────────────────────────────
 *  1. Wrong package  → HUD "WRONG APP"
 *  2. Exact match on currStep → advance HUD to show nextStep
 *  3. Forward leap (screen == nextStep) → skip index, update HUD
 *  4. Completion (last step) → HUD "COMPLETE ✓", auto-hide after 3 s
 *  5. Off-track (correct package, unknown screen) → HUD "OFF TRACK"
 *
 * ── Thread Safety ────────────────────────────────────────────────────────────
 *   [onScreenChanged] may be called from any thread.
 *   All HUD calls are already thread-safe in NavigationHudOverlay.
 *   Internal state mutations are confined to the main thread via mainHandler.
 */
object NavigationStateMachine {

    private const val TAG = "[NavigationEngine]"

    private val mainHandler = Handler(Looper.getMainLooper())

    // ── Injected reference to the HUD — set by FloatingOverlayService ─────────
    // Lateinit is safe: it is set in onCreate() before any event can arrive.
    private lateinit var hud: NavigationHudOverlay

    // ── Navigation session state ──────────────────────────────────────────────
    @Volatile var isNavigating: Boolean = false
        private set

    private var targetPackage:    String       = ""
    private var pathSteps:        List<String> = emptyList()
    private var currentIndex:     Int          = 0
    private var prevStep:         String?      = null
    private var currStep:         String       = ""
    private var nextStep:         String?      = null
    private var lastCorrectStep:  String?      = null

    // ══════════════════════════════════════════════════════════════════════════
    //  Lifecycle — called by FloatingOverlayService
    // ══════════════════════════════════════════════════════════════════════════

    /** Wire the HUD reference once at service creation. */
    fun attachHud(overlay: NavigationHudOverlay) {
        hud = overlay
        Log.d(TAG, "HUD reference attached to NavigationStateMachine")
    }

    /**
     * Begin a new navigation session.
     *
     * @param resolvedPath  Ordered step list, e.g. ["Home","Settings","System","About phone"]
     * @param pkgTarget     Package fragment that identifies the target app,
     *                      e.g. "settings" (will be matched with String.contains()).
     */
    fun startNavigation(resolvedPath: List<String>, pkgTarget: String) {
        if (resolvedPath.isEmpty()) {
            Log.w(TAG, "startNavigation() called with empty path — ignored")
            return
        }
        postToMain {
            targetPackage   = pkgTarget.trim().lowercase()
            pathSteps       = resolvedPath
            currentIndex    = 0
            prevStep        = null
            currStep        = pathSteps[0]
            nextStep        = pathSteps.getOrNull(1)
            lastCorrectStep = null
            isNavigating    = true

            Log.i(TAG, "Navigation STARTED — ${pathSteps.size} steps toward '$targetPackage'")
            Log.i(TAG, "Steps: ${pathSteps.joinToString(" → ")}")
            Log.i(TAG, "Step 1/${pathSteps.size}: '$currStep'  next='$nextStep'")

            hud.updateHud(
                stepHeader        = "STEP 1 OF ${pathSteps.size}",
                actionInstruction = currStep,
                subTag            = "start"
            )
            hud.show()
        }
    }

    /**
     * Stop the active session and dismiss the HUD.
     * Safe to call when no session is active.
     */
    fun stopNavigation() {
        postToMain {
            if (!isNavigating) {
                Log.d(TAG, "stopNavigation() — no active session, no-op")
                return@postToMain
            }
            isNavigating    = false
            targetPackage   = ""
            pathSteps       = emptyList()
            currentIndex    = 0
            prevStep        = null
            currStep        = ""
            nextStep        = null
            lastCorrectStep = null
            hud.hide()
            Log.i(TAG, "Navigation STOPPED — state reset, HUD hidden")
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Screen event — called by UiTreeAccessibilityService
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Evaluate a screen-change event against the active navigation session.
     *
     * Called from [UiTreeAccessibilityService] on every TYPE_WINDOW_STATE_CHANGED
     * event that passes the main filter.
     *
     * @param activePackage     Package name of the foreground window, e.g. "com.android.settings"
     * @param currentScreenName Human-readable screen name resolved by GraphStateMachine,
     *                          e.g. "System", "About phone", "MainActivity"
     */
    fun onScreenChanged(activePackage: String, currentScreenName: String) {
        if (!isNavigating) return    // fast exit — no session active

        Log.d(TAG, "onScreenChanged: pkg='$activePackage' screen='$currentScreenName' " +
                   "| navigating toward '$targetPackage' step[$currentIndex]='$currStep'")

        postToMain {
            if (!isNavigating) return@postToMain  // re-check after dispatch

            // ── 1. Package check ──────────────────────────────────────────────
            val pkgMatches = activePackage.contains(targetPackage, ignoreCase = true) ||
                             targetPackage.contains(activePackage.substringAfterLast('.'), ignoreCase = true)

            if (!pkgMatches) {
                Log.d(TAG, "Package mismatch: active='$activePackage', expected='$targetPackage'")
                hud.updateHud(
                    stepHeader        = "WRONG APP",
                    actionInstruction = "Open ${targetPackage.replaceFirstChar { it.uppercase() }} or return to Home",
                    subTag            = "pkg-mismatch"
                )
                return@postToMain
            }

            val screenNorm = currentScreenName.trim().lowercase()
            val currNorm   = currStep.trim().lowercase()
            val nextNorm   = nextStep?.trim()?.lowercase()

            // ── 2. Exact / fuzzy match on currStep ────────────────────────────
            if (fuzzyMatch(screenNorm, currNorm)) {
                lastCorrectStep = currStep
                val stepNum     = currentIndex + 1

                if (nextStep == null) {
                    // Last step reached — navigation complete
                    Log.i(TAG, "COMPLETE at step $stepNum/'$currStep'")
                    hud.updateHud(
                        stepHeader        = "COMPLETE ✓",
                        actionInstruction = "You're done!",
                        subTag            = "complete"
                    )
                    isNavigating = false
                    // Auto-hide after 3 s
                    mainHandler.postDelayed({ hud.hide() }, 3000L)
                    return@postToMain
                }

                Log.d(TAG, "On track at step $stepNum: '$currStep'. Next: '$nextStep'")
                hud.updateHud(
                    stepHeader        = "STEP $stepNum OF ${pathSteps.size}",
                    actionInstruction = "Look for '${nextStep!!}'",
                    subTag            = "on-track"
                )
                return@postToMain
            }

            // ── 3. Forward leap — user is already at nextStep ─────────────────
            if (nextNorm != null && fuzzyMatch(screenNorm, nextNorm)) {
                prevStep    = currStep
                currentIndex++
                currStep    = pathSteps[currentIndex]
                nextStep    = pathSteps.getOrNull(currentIndex + 1)
                lastCorrectStep = currStep

                val stepNum = currentIndex + 1

                if (nextStep == null) {
                    // Forward leap landed on last step
                    Log.i(TAG, "Forward leap to FINAL step $stepNum/'$currStep'")
                    hud.updateHud(
                        stepHeader        = "COMPLETE ✓",
                        actionInstruction = "You're done!",
                        subTag            = "leap-complete"
                    )
                    isNavigating = false
                    mainHandler.postDelayed({ hud.hide() }, 3000L)
                    return@postToMain
                }

                Log.d(TAG, "Advanced (leap) to step $stepNum: '$currStep'  next='$nextStep'")
                hud.updateHud(
                    stepHeader        = "STEP $stepNum OF ${pathSteps.size}",
                    actionInstruction = "'$nextStep'",
                    subTag            = "leap"
                )
                return@postToMain
            }

            // ── 4. Off-track — correct package, unknown screen ────────────────
            val lcs = lastCorrectStep
            if (lcs != null) {
                Log.d(TAG, "Diverged to '$currentScreenName'. Backtrack to '$lcs'")
                hud.updateHud(
                    stepHeader        = "OFF TRACK",
                    actionInstruction = "Press Back to return to '$lcs'",
                    subTag            = "off-track"
                )
            } else {
                Log.d(TAG, "Off-track at '$currentScreenName' (no correct step yet)")
                hud.updateHud(
                    stepHeader        = "OFF TRACK",
                    actionInstruction = "Return to Home / Main Screen",
                    subTag            = "off-track-no-anchor"
                )
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Legacy compatibility (used by FloatingOverlayService Stop button)
    // ══════════════════════════════════════════════════════════════════════════

    /** Alias for [stopNavigation] — kept so existing call sites compile. */
    fun stop() = stopNavigation()

    /** True while a session is active. */
    fun isRunning(): Boolean = isNavigating

    // ══════════════════════════════════════════════════════════════════════════
    //  Matching
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Fuzzy match between the screen name reported by the accessibility service
     * and the expected step name from the resolved path.
     *
     * Strategy (in priority order):
     *   1. Exact equality (case-insensitive already normalised by caller)
     *   2. One contains the other (handles "About phone" inside "System > About phone" labels)
     *   3. Any single word in `expected` appears in `actual` when expected.length < 15
     *      (handles abbreviated OEM screen labels)
     */
    private fun fuzzyMatch(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        if (actual.contains(expected) || expected.contains(actual)) return true
        // Word-level fallback for short identifiers
        if (expected.length < 15) {
            val words = expected.split(" ", "_", "-").filter { it.length > 2 }
            if (words.isNotEmpty() && words.all { actual.contains(it) }) return true
        }
        return false
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  Utility
    // ══════════════════════════════════════════════════════════════════════════

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
