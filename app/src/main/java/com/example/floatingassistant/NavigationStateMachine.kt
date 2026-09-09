package com.example.floatingassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object NavigationStateMachine {

    private const val TAG = "[NavigationEngine]"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var hud: NavigationHudOverlay
    private lateinit var context: Context
    private lateinit var elementFinder: ElementFinderEngine

    @Volatile var lastKnownActivePackage: String = ""
    @Volatile var isNavigating: Boolean = false
        private set

    private var targetPackage: String = ""
    private var targetAppLabel: String = ""  // PM-resolved label, e.g. "Clock"
    private var initialTask: String = ""
    private var pathSteps: MutableList<String> = mutableListOf()
    private var currentIndex: Int = 0
    private var prevStep: String? = null
    private var currStep: String = ""
    private var nextStep: String? = null
    private var lastCorrectStep: String? = null
    private var activeScreenName: String = ""
    @Volatile var currentTargetBounds: Rect? = null

    fun attachHud(overlay: NavigationHudOverlay, ctx: Context) {
        hud = overlay
        context = ctx.applicationContext
        elementFinder = ElementFinderEngine(context)
        Log.d(TAG, "HUD + Context attached to NavigationStateMachine")
    }

    fun startNavigation(resolvedPath: List<String>, pkgTarget: String, exactTask: String = "") {
        if (resolvedPath.isEmpty()) return
        postToMain {
            targetPackage = pkgTarget.trim().lowercase()
            // Pre-resolve the human-readable label for this target package
            targetAppLabel = MainFilter.resolveAppLabel(context, pkgTarget).also {
                Log.i(TAG, "Target resolved: pkg='$targetPackage' label='$it'")
            }
            initialTask = exactTask
            pathSteps = resolvedPath.toMutableList()
            lastCorrectStep = null
            isNavigating = true

            // ── First-run home marking ─────────────────────────────────────────
            // On the very first launch ever, if the path starts with Home, prompt
            // the user to go to their home screen so we can reference it later.
            val prefs = context.getSharedPreferences("nav_prefs", android.content.Context.MODE_PRIVATE)
            val homeEverMarked = prefs.getBoolean("home_marked", false)
            if (!homeEverMarked) {
                prefs.edit().putBoolean("home_marked", true).apply()
                val firstStep = resolvedPath.firstOrNull()?.trim()?.lowercase()
                if (firstStep == "home" || firstStep == null) {
                    hud.updateHud(
                        stepHeader        = "FIRST TIME SETUP",
                        actionInstruction = "Go to your home screen to begin",
                        breadcrumb        = null,
                        contextText       = "Press the Home button on your phone",
                        subTag            = "first-run-home"
                    )
                    hud.show()
                    // Don't start the main navigation yet — wait for the next onScreenChanged
                    // which will detect the launcher and fire the real start sequence.
                    return@postToMain
                }
            }

            Log.i(TAG, "Navigation STARTED — ${pathSteps.size} steps toward '$targetAppLabel' ($targetPackage)")

            val activePkg     = lastKnownActivePackage
            val launcherPkg   = getDefaultLauncherPackage()
            val isOnLauncher  = launcherPkg != null && activePkg.startsWith(launcherPkg)
            val alreadyInApp  = activePkg.isNotEmpty() && isTargetApp(activePkg, null)

            // ── Determine start position ──────────────────────────────────────
            val startIdx: Int
            val startHeader: String
            val startInstruction: String

            when {
                alreadyInApp -> {
                    // User is in the correct app. Use the CURRENT SCREEN NAME to
                    // find where in the path they already are — then start from
                    // the NEXT step after that position.
                    val screenNorm = activeScreenName.trim().lowercase()
                    val nearest = if (screenNorm.isNotEmpty()) findNearestStep(screenNorm) else null
                    val nearestIdx = nearest?.first ?: -1

                    startIdx = when {
                        // Screen matches a known step — start from the step AFTER it
                        nearestIdx >= 0 -> {
                            Log.i(TAG, "Fast-forward: screen='$activeScreenName' matches step[$nearestIdx]='${nearest!!.second}' — starting from ${nearestIdx + 1}")
                            (nearestIdx + 1).coerceAtMost(pathSteps.size - 1)
                        }
                        // Screen unrecognized — skip Home and AppRoot steps only
                        else -> {
                            var idx = 0
                            while (idx < pathSteps.size) {
                                val sn = pathSteps[idx].trim().lowercase()
                                val isHome    = sn == "home" || (launcherPkg != null && launcherPkg.contains(sn))
                                val isAppRoot = appRootFuzzyMatch(sn)
                                if (!isHome && !isAppRoot) break
                                idx++
                            }
                            idx.coerceAtMost(pathSteps.size - 1)
                        }
                    }

                    startHeader      = "STEP ${startIdx + 1} OF ${pathSteps.size}"
                    startInstruction = "Look for '${pathSteps[startIdx]}'"
                }

                isOnLauncher -> {
                    // User is on the home screen
                    startIdx         = 0
                    startHeader      = "ON HOME"
                    startInstruction = "Open $targetAppLabel"
                }

                else -> {
                    // User is in a DIFFERENT app entirely
                    startIdx         = 0
                    startHeader      = "WRONG APP"
                    startInstruction = "Close this and open $targetAppLabel"
                }
            }

            currentIndex = startIdx
            prevStep     = if (startIdx > 0) pathSteps[startIdx - 1] else null
            currStep     = pathSteps[currentIndex]
            nextStep     = pathSteps.getOrNull(currentIndex + 1)

            Log.i(TAG, "Navigation READY: startIdx=$startIdx currStep='$currStep' nextStep='$nextStep'")

            hud.updateHud(
                stepHeader        = startHeader,
                actionInstruction = startInstruction,
                breadcrumb        = buildBreadcrumb(),
                contextText       = if (alreadyInApp) "Next: ${nextStep ?: "Done"}" else null,
                subTag            = if (startIdx > 0) "smart-fast-forward" else "start"
            )
            hud.show()

            if (alreadyInApp) {
                triggerSearchPipeline(currStep, activePkg, activeScreenName)
            }
        }
    }


    fun stopNavigation() {
        postToMain {
            if (!isNavigating) return@postToMain
            isNavigating = false
            targetPackage = ""
            targetAppLabel = ""
            pathSteps = mutableListOf()
            currentIndex = 0
            prevStep = null
            currStep = ""
            nextStep = null
            lastCorrectStep = null
            activeScreenName = ""
            hud.hide()
        }
    }

    fun stop() = stopNavigation()
    fun isRunning() = isNavigating

    /**
     * Called by UiTreeAccessibilityService on every TYPE_VIEW_CLICKED event.
     *
     * This is the PRIMARY advancement signal for overlay / bottom-sheet screens
     * (e.g. WhatsApp Settings sliding in as a fragment, 3-dot menus, dialogs).
     * These screens never fire TYPE_WINDOW_STATE_CHANGED, so onScreenChanged()
     * can't detect them. But we DO get a click event when the user taps an item.
     *
     * If [tappedLabel] fuzzy-matches [currStep], we treat it as "user completed
     * this step" and advance the state machine forward.
     *
     * A short de-dupe guard prevents double-advancing if both onElementTapped
     * and onScreenChanged fire for the same action (e.g. a full-screen transition
     * that also has a click event).
     */
    fun onElementTapped(tappedLabel: String, fromPackage: String) {
        if (!isNavigating) return

        val tapNorm  = tappedLabel.trim().lowercase()
        val currNorm = currStep.trim().lowercase()

        // Only act if the tap matches the current step
        if (!fuzzyMatch(tapNorm, currNorm)) return

        // Ignore taps from our own overlay or system UI
        if (fromPackage == context.packageName) return
        if (fromPackage.startsWith("com.android.systemui")) return

        Log.i(TAG, "onElementTapped: tapped='$tappedLabel' matches currStep='$currStep' — advancing")

        postToMain {
            if (!isNavigating) return@postToMain
            // Guard: if we already advanced past this step (e.g. screen changed first), skip
            if (!fuzzyMatch(currStep.trim().lowercase(), currNorm)) return@postToMain

            lastCorrectStep = currStep
            advanceStep(currentIndex)
        }
    }

    /**
     * Called from UiTreeAccessibilityService on every WINDOW_STATE_CHANGED event.
     *
     * @param activePackage  Raw Android package name, e.g. "com.android.deskclock"
     * @param currentScreenName  Human-readable semantic root, e.g. "Clock-Alarm"
     * @param activeAppLabel     PM-resolved display name, e.g. "Clock" (may be empty)
     */
    fun onScreenChanged(
        activePackage: String,
        currentScreenName: String,
        activeAppLabel: String = ""
    ) {
        if (!isNavigating) return
        lastKnownActivePackage = activePackage

        postToMain {
            if (!isNavigating) return@postToMain

            val launcherPkg = getDefaultLauncherPackage()
            val isLauncherEvent = launcherPkg != null && activePackage.startsWith(launcherPkg)

            activeScreenName = if (isLauncherEvent) "Home" else currentScreenName

            val currStepIsHome = currStep.trim().equals("home", ignoreCase = true)
            // Use the PM label if available, otherwise fall back to package-level matching
            val inTargetApp = isTargetApp(activePackage, activeAppLabel.ifBlank { null })

            Log.d(TAG, "Screen: pkg='$activePackage' label='$activeAppLabel' screen='$activeScreenName' | currStep='$currStep' inTarget=$inTargetApp isLauncher=$isLauncherEvent")

            // ── Fast-forward: already in the target app before navigation tapped ──
            if (currentIndex == 0 && inTargetApp && !isLauncherEvent) {
                var startIdx = 0
                while (startIdx < pathSteps.size) {
                    val stepNorm = pathSteps[startIdx].trim().lowercase()
                    val isHome = stepNorm == "home" || (launcherPkg != null && launcherPkg.contains(stepNorm))
                    val isAppRoot = appRootFuzzyMatch(stepNorm)
                    if (!isHome && !isAppRoot) break
                    startIdx++
                }
                startIdx = startIdx.coerceAtMost(pathSteps.size - 1)

                if (startIdx > currentIndex) {
                    currentIndex = startIdx
                    prevStep = if (startIdx > 0) pathSteps[startIdx - 1] else null
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    lastCorrectStep = prevStep
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Look for '$currStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Current: $activeScreenName",
                        subTag            = "dynamic-fast-forward"
                    )
                    triggerSearchPipeline(currStep, activePackage, activeScreenName)
                    return@postToMain
                }
            }

            // ── Off-app states ─────────────────────────────────────────────────
            if (isLauncherEvent && !currStepIsHome) {
                hud.updateHud(
                    stepHeader        = "ON HOME",
                    actionInstruction = "Open $targetAppLabel",
                    breadcrumb        = buildBreadcrumb(),
                    contextText       = "Expected: $currStep",
                    subTag            = "on-home-diverged"
                )
                return@postToMain
            }

            if (!currStepIsHome && !inTargetApp && !isLauncherEvent) {
                val actualName = activeAppLabel.ifBlank { activeScreenName }
                hud.updateHud(
                    stepHeader        = "WRONG APP",
                    actionInstruction = "Open $targetAppLabel or return to Home",
                    breadcrumb        = buildBreadcrumb(),
                    contextText       = "Current: $actualName | Expected: $targetAppLabel",
                    subTag            = "pkg-mismatch"
                )
                return@postToMain
            }

            // ── On-app screen matching ─────────────────────────────────────────
            val screenNorm = activeScreenName.trim().lowercase()
            val currNorm   = currStep.trim().lowercase()
            val nextNorm   = nextStep?.trim()?.lowercase()

            // Home step confirmation
            if (isLauncherEvent && currStepIsHome) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // App-root step: being in the target app is enough to advance
            if (appRootFuzzyMatch(currNorm) && inTargetApp) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // Exact/fuzzy screen name match for current step
            if (fuzzyMatch(screenNorm, currNorm)) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // Skip-ahead: next step matches current screen
            if (nextNorm != null && fuzzyMatch(screenNorm, nextNorm)) {
                prevStep = currStep
                currentIndex++
                currStep = pathSteps[currentIndex]
                nextStep = pathSteps.getOrNull(currentIndex + 1)
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // We're in the right app but couldn't match current/next step name.
            // Before searching for elements, check if the user went BACKWARD
            // (e.g. navigated to Settings when currStep is Chat Backup).
            if (inTargetApp) {
                val nearest = findNearestStep(screenNorm)
                val nearestIdx = nearest?.first ?: -1

                if (nearestIdx in 0 until currentIndex) {
                    // User is behind — they went back. Guide them forward.
                    val nearestName = nearest!!.second
                    val stepsAhead  = currentIndex - nearestIdx
                    Log.i(TAG, "User went back: at '$nearestName' (idx $nearestIdx), need '$currStep' ($stepsAhead ahead)")
                    val action = if (stepsAhead == 1)
                        "Tap '$currStep' to continue"
                    else
                        "Return to '$nearestName', then tap '$currStep'"
                    hud.updateHud(
                        stepHeader        = "GO FORWARD",
                        actionInstruction = action,
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "At '$nearestName' | Need: '$currStep'",
                        subTag            = "went-back"
                    )
                } else {
                    // Screen unrecognized but we're in the right app — hunt for the element
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Look for '$currStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Current: $activeScreenName",
                        subTag            = "in-app-searching"
                    )
                    triggerSearchPipeline(currStep, activePackage, activeScreenName)
                }
                return@postToMain
            }


            // ── Nearest-step recovery ──────────────────────────────────────────
            // Scan ALL path steps and find which one the current screen is
            // closest to. This gives hyper-specific guidance:
            //   "You're near 'Settings'. Go back to Settings, then tap 'Chat Backup'."
            // rather than the generic "Press Back to Home".
            val nearest = findNearestStep(screenNorm)
            val nearestName = nearest?.second
            val nearestIdx  = nearest?.first ?: -1

            when {
                // User is ON a step that is behind the current position — they went back
                nearestIdx in 0 until currentIndex && nearestName != null -> {
                    val stepsAhead = currentIndex - nearestIdx
                    val action = if (stepsAhead == 1)
                        "Tap '$currStep' to continue"
                    else
                        "Go to '$currStep' (${stepsAhead} steps ahead)"
                    hud.updateHud(
                        stepHeader        = "GO FORWARD",
                        actionInstruction = action,
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "You're at '$nearestName' | Need: '$currStep'",
                        subTag            = "nearest-step-forward"
                    )
                    Log.i(TAG, "Off-track recovery: nearest='$nearestName' idx=$nearestIdx, guiding to currStep='$currStep'")
                }

                // User is beyond the current step — they skipped ahead
                nearestIdx > currentIndex && nearestName != null -> {
                    // Fast-forward to the nearest matched step
                    Log.i(TAG, "Off-track recovery: user jumped ahead to nearestIdx=$nearestIdx, fast-forwarding")
                    currentIndex = nearestIdx
                    prevStep = if (nearestIdx > 0) pathSteps[nearestIdx - 1] else null
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    lastCorrectStep = nearestName
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Look for '$currStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Jumped ahead to '$nearestName'",
                        subTag            = "nearest-step-jumped"
                    )
                    triggerSearchPipeline(currStep, activePackage, activeScreenName)
                }

                // No close match found — fall back to lastCorrectStep anchor
                lastCorrectStep != null -> {
                    hud.updateHud(
                        stepHeader        = "OFF TRACK",
                        actionInstruction = "Go back to '${lastCorrectStep}'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Current: $activeScreenName | Need: $currStep",
                        subTag            = "off-track-anchor"
                    )
                }

                // Completely lost — guide to the app entry point
                else -> {
                    hud.updateHud(
                        stepHeader        = "OFF TRACK",
                        actionInstruction = "Open $targetAppLabel to restart",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Current: $activeScreenName | Expected: $currStep",
                        subTag            = "off-track-no-anchor"
                    )
                }
            }
        }
    }

    // ── Breadcrumb builder ─────────────────────────────────────────────────────

    /**
     * Builds a compact breadcrumb string showing past → [current] → next.
     * Example: "Home → [WhatsApp] → Settings"
     */
    private fun buildBreadcrumb(): String {
        val parts = mutableListOf<String>()
        if (currentIndex > 0) {
            parts.add(pathSteps[currentIndex - 1])
        }
        parts.add("[${pathSteps.getOrElse(currentIndex) { currStep }}]")
        val n = nextStep
        if (n != null) parts.add(n)
        return parts.joinToString(" → ")
    }

    // ── Nearest-step finder ────────────────────────────────────────────────────

    /**
     * Scans every step in [pathSteps] and returns the one whose name best
     * matches [screenNorm] (lowercase current screen name).
     *
     * Scoring (higher = better match):
     *   3 pts — exact match
     *   2 pts — one string contains the other
     *   1 pt  — any shared token (word longer than 2 chars)
     *   0 pts — no overlap
     *
     * Returns Pair(stepIndex, stepName) of the highest-scoring step, or null
     * if no step scores above 0 (completely unrecognised screen).
     */
    private fun findNearestStep(screenNorm: String): Pair<Int, String>? {
        var bestScore = 0
        var bestIdx   = -1
        var bestName  = ""

        pathSteps.forEachIndexed { idx, step ->
            val stepNorm = step.trim().lowercase()
            val score = when {
                stepNorm == screenNorm                            -> 3
                stepNorm.contains(screenNorm) ||
                    screenNorm.contains(stepNorm)                -> 2
                else -> {
                    // Token overlap: count shared words longer than 2 chars
                    val stepTokens   = stepNorm.split(" ", "-", "_").filter { it.length > 2 }
                    val screenTokens = screenNorm.split(" ", "-", "_").filter { it.length > 2 }
                    val shared = stepTokens.count { tok -> screenTokens.any { it.contains(tok) || tok.contains(it) } }
                    if (shared > 0) 1 else 0
                }
            }

            if (score > bestScore) {
                bestScore = score
                bestIdx   = idx
                bestName  = step
            }
        }

        Log.d(TAG, "findNearestStep('$screenNorm') → idx=$bestIdx step='$bestName' score=$bestScore")
        return if (bestScore > 0) Pair(bestIdx, bestName) else null
    }

    // ── Step advancement ───────────────────────────────────────────────────────

    private fun advanceStep(confirmedIndex: Int) {
        val stepNum = confirmedIndex + 1

        if (nextStep == null) {
            Log.i(TAG, "COMPLETE — step $stepNum/'$currStep' was the last step")
            hud.showVerificationPrompt(
                onYes = { hud.resetFocusability(); handleSuccess() },
                onNo  = {
                    hud.resetFocusability()
                    // Per spec: Groq healing disabled for now — ask user to retry manually
                    hud.updateHud(
                        stepHeader        = "NOT FOUND",
                        actionInstruction = "Try again or restart",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Tap the bubble to submit a new command",
                        subTag            = "destination-no"
                    )
                    isNavigating = false
                }
            )
            return
        }

        prevStep = currStep
        currentIndex++
        currStep = pathSteps[currentIndex]
        nextStep = pathSteps.getOrNull(currentIndex + 1)

        hud.updateHud(
            stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
            actionInstruction = "Look for '$currStep'",
            breadcrumb        = buildBreadcrumb(),
            contextText       = "Next: ${nextStep ?: "Done"}",
            subTag            = "on-track"
        )

        triggerSearchPipeline(currStep, lastKnownActivePackage, activeScreenName)
    }

    // ── Element search pipeline ────────────────────────────────────────────────

    private fun triggerSearchPipeline(targetStep: String, activePkg: String, activeScreen: String) {
        if (!::elementFinder.isInitialized) return

        stateMachineScope.launch(Dispatchers.IO) {
            delay(400)
            if (!isNavigating) return@launch

            val result = elementFinder.findElement(targetStep, activePkg, activeScreen, prevStep ?: "")
            currentTargetBounds = result.bounds

            if (result.found) {
                postToMain {
                    if (!isNavigating) return@postToMain
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Tap '$targetStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Found on screen",
                        subTag            = "target-found"
                    )
                }
                return@launch
            }


            // ── IMPORTANT: Do NOT auto-advance here based on next-step visibility.
            // Advancement is ONLY driven by onScreenChanged() when the screen name
            // matches a path step. Auto-advancing from the search pipeline caused
            // a chain reaction that skipped all steps to the last one.
            //
            // If the current step isn't visible, build a combined hint so the user
            // can take 1–2 actions in one go.

            postToMain {
                if (!isNavigating) return@postToMain
                val nStep = nextStep
                if (elementFinder.hasScrollableContainer()) {
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Scroll to find '$targetStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Not visible yet",
                        subTag            = "scroll-directive"
                    )
                } else if (nStep != null) {
                    // Combined 2-step hint: find the current element, then immediately tap next
                    val combinedHint = buildCombinedHint(targetStep, nStep)
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = combinedHint,
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Not found — try combined action",
                        subTag            = "combined-hint"
                    )
                } else {
                    // Last step and not found — keep it simple
                    hud.updateHud(
                        stepHeader        = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Find and tap '$targetStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Last step",
                        subTag            = "final-step-hint"
                    )
                }
            }
        }
    }

    /**
     * Builds a short combined 2-step instruction for when the current element
     * can't be found automatically.
     * Examples:
     *   "Tap '3 dots' → Settings"
     *   "Find 'Menu' → tap 'Chat Backup'"
     */
    private fun buildCombinedHint(curr: String, next: String): String {
        // Keep it short: if curr is a symbol/short word use "Tap", else "Find"
        val verb = if (curr.length <= 6 || curr.contains("dot") || curr.contains("menu", ignoreCase = true))
            "Tap '$curr'" else "Find '$curr'"
        return "$verb → $next"
    }


    // ── Groq Healing (DISABLED — re-enable after core loop is stable) ─────────
    // triggerHealer() was removed per spec. Re-add GroqHealer.requestHealing()
    // call here once the 3-layer search + scroll loop is proven flawless.

    // ── Destination verification & cloud sync ─────────────────────────────────

    private fun handleSuccess() {
        stateMachineScope.launch(Dispatchers.IO) {
            val pathStr = pathSteps.joinToString(" -> ")
            Log.i("[NetworkClient]", "Uploading successful path to Firestore: $pathStr")
            CloudPathDatabase.addEntry(targetPackage, initialTask, pathStr)

            postToMain {
                hud.updateHud(
                    stepHeader        = "SUCCESS ✓",
                    actionInstruction = "Path saved!",
                    breadcrumb        = buildBreadcrumb(),
                    subTag            = "saved"
                )
                isNavigating = false
                mainHandler.postDelayed({ hud.hide() }, 3000L)
            }
        }
    }

    // ── App matching helpers ───────────────────────────────────────────────────

    /**
     * Returns true if [activePackage] belongs to the navigation target.
     *
     * Matching is done in layers (most reliable first):
     * 1. Package string contains target package string (e.g. "com.whatsapp" ⊇ "whatsapp")
     * 2. PM label matches target package or targetAppLabel (handles deskclock → "Clock")
     * 3. Fuzzy token overlap between appLabel and targetAppLabel
     */
    fun isTargetApp(activePackage: String, activeAppLabel: String? = null): Boolean {
        val pkg    = activePackage.lowercase()
        val target = targetPackage.lowercase()
        val label  = targetAppLabel.lowercase()

        // Layer 1: direct package substring
        if (pkg.contains(target) || target.contains(pkg)) return true

        // Layer 2: PM label exact / contains match
        val pLabel = (activeAppLabel ?: "").lowercase()
        if (pLabel.isNotBlank()) {
            if (pLabel == label || pLabel.contains(label) || label.contains(pLabel)) return true
        }

        // Layer 3: resolve label fresh from PM and compare
        return try {
            val resolvedLabel = MainFilter.resolveAppLabel(context, activePackage).lowercase()
            resolvedLabel == label ||
                resolvedLabel.contains(label) ||
                label.contains(resolvedLabel)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true when [stepNorm] (a path step in lowercase) refers to the
     * root/launch step of the target app — e.g. "clock", "whatsapp", "gmail".
     */
    private fun appRootFuzzyMatch(stepNorm: String): Boolean {
        val label  = targetAppLabel.lowercase()
        val pkgEnd = targetPackage.substringAfterLast('.').lowercase()
        return stepNorm == label ||
               stepNorm == pkgEnd ||
               stepNorm == targetPackage ||
               label.contains(stepNorm) ||
               stepNorm.contains(label) ||
               pkgEnd.contains(stepNorm) ||
               stepNorm.contains(pkgEnd)
    }

    /**
     * Fuzzy screen name match.
     * Handles "Clock-Alarm" matching step "Alarm", or "Chats" matching "WhatsApp".
     * Tokenises both sides on "-" and " " and checks for shared tokens.
     */
    private fun fuzzyMatch(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        if (actual.contains(expected) || expected.contains(actual)) return true

        // Token-level: split on dash, space, underscore and require all expected tokens in actual
        val expectedTokens = expected.split(" ", "-", "_").filter { it.length > 2 }
        if (expectedTokens.isNotEmpty() && expectedTokens.all { tok -> actual.contains(tok) }) return true

        // Reverse: all actual tokens in expected
        val actualTokens = actual.split(" ", "-", "_").filter { it.length > 2 }
        if (actualTokens.isNotEmpty() && actualTokens.all { tok -> expected.contains(tok) }) return true

        return false
    }

    // ── Launcher detection ────────────────────────────────────────────────────

    fun getDefaultLauncherPackage(): String? {
        if (!::context.isInitialized) return null
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            info?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    // ── Utilities ──────────────────────────────────────────────────────────────

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
