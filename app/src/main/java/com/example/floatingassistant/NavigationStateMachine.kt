package com.example.floatingassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    @Volatile var activeScreenName: String = ""
    @Volatile var isCurrentScreenUnrecognized: Boolean = false
    @Volatile var currentTargetBounds: Rect? = null
    @Volatile var lastAdvanceTimeMs: Long = 0L
    private var wasRerouted = false
    private var pathSource: String = "local"
    @Volatile private var connectedTargetPackages: Set<String> = emptySet()

    // ── Stateful Navigation Execution Models ──────────────────────────────────
    data class NavExecutionNode(
        val stepIndex: Int,
        val screenTitle: String,
        val targetElement: String,
        val nextScreenTitle: String?,
        val parentIndex: Int?
    )

    sealed class TrackerState {
        data class OnRoute(val node: NavExecutionNode) : TrackerState()
        data class Diverged(
            val originNode: NavExecutionNode,
            val currentScreenTitle: String,
            val divergedStack: List<String> = listOf(currentScreenTitle),
            val backStepsRequired: Int = divergedStack.size,
            val nextPlannedTarget: String
        ) : TrackerState()
        data class OffApp(val expectedApp: String, val currentApp: String) : TrackerState()
        data class DestinationReached(val finalNode: NavExecutionNode) : TrackerState()
    }

    @Volatile var currentTrackerState: TrackerState? = null
    private val executionTree: MutableList<NavExecutionNode> = mutableListOf()

    // [PERMANENT FIX] Do not remove this! Format recovery guidance when user diverges from route
    private fun formatRecoveryGuidance(originTitle: String, targetElement: String, backSteps: Int): Pair<String, String> {
        val action = if (backSteps <= 1) {
            "Press Back to return to $originTitle"
        } else {
            "Press Back ($backSteps steps) to return to $originTitle"
        }
        val context = "Then look for '$targetElement'"
        return Pair(action, context)
    }

    // [PERMANENT FIX] Do not remove this! Dynamically query Dijkstra in SQLite to calculate required back steps when diverged
    private fun updateDivergedRecovery(originTitle: String, currentScreen: String, targetElement: String, fallbackSteps: Int) {
        stateMachineScope.launch(Dispatchers.IO) {
            var backSteps = fallbackSteps
            try {
                if (::context.isInitialized && targetPackage.isNotBlank()) {
                    val currentScreenId = "$targetPackage::$currentScreen"
                    val result = SearchPathEngine.findPath(
                        context = context,
                        targetApp = targetPackage,
                        destinationScreen = originTitle,
                        currentScreenId = currentScreenId
                    )
                    if (result.found && result.steps.isNotEmpty()) {
                        val backCount = result.steps.count { it.equals("BACK", ignoreCase = true) }
                        if (backCount > 0) {
                            backSteps = backCount
                            val state = currentTrackerState
                            if (state is TrackerState.Diverged) {
                                currentTrackerState = state.copy(backStepsRequired = backSteps)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.d(TAG, "updateDivergedRecovery Dijkstra check failed: ${e.message}")
            }

            postToMain {
                if (!isNavigating) return@postToMain
                val (action, ctx) = formatRecoveryGuidance(originTitle, targetElement, backSteps)
                val currentTap = lastWrongTapText?.trim()
                val hasTap = !currentTap.isNullOrEmpty() && !currentTap.equals("null", ignoreCase = true) && !currentTap.equals("nil", ignoreCase = true)
                val instruction = if (hasTap) "You tapped '$currentTap'" else action
                val contextTxt = if (hasTap) action else ctx
                hud.updateHud(
                    stepHeader        = "WRONG PATH",
                    actionInstruction = instruction,
                    breadcrumb        = null, // [PERMANENT FIX] Hide future breadcrumb in wrong-path state to eliminate visual clutter
                    contextText       = contextTxt,
                    subTag            = "unrecognized-screen-back",
                    hudState          = NavigationHudOverlay.HudState.OFF_TRACK,
                    onHelpRequested   = { onRerouteRequested?.invoke(targetPackage, initialTask) ?: Unit }
                )
                // [PERMANENT FIX] Do not remove this! Highlight system navbar or toolbar Back button on wrong path recovery
                highlightBackAction()
            }
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this! Visually highlights the system navbar or toolbar Back button
     * when the user is off-track, diverged, or instructed to press Back.
     */
    private fun highlightBackAction() {
        MarkerScout.shutdown(isPermanentStop = false)
        stateMachineScope.launch(Dispatchers.IO) {
            val backTarget = if (::elementFinder.isInitialized) elementFinder.findUniversalBackTarget() else null
            postToMain {
                if (!isNavigating) return@postToMain
                if (backTarget != null) {
                    val (bounds, isSystemNav) = backTarget
                    Log.i(TAG, "highlightBackAction: marking Back button at $bounds (isSystemNav=$isSystemNav)")
                    marker?.showMarker(bounds, isSystemNav = isSystemNav)
                } else {
                    marker?.hideMarker()
                }
            }
        }
    }

    // [PERMANENT FIX] Do not remove this! Format HUD step header with short source keyword: (local), (db), or (ai)
    private fun formatStepHeader(header: String = "STEP ${currentIndex + 1} OF ${pathSteps.size}"): String {
        return if (pathSource.isNotBlank()) "$header ($pathSource)" else header
    }

    // ── Callbacks ──
    var onRerouteRequested: ((app: String, task: String) -> Unit)? = null

    private var marker: ElementMarkerOverlay? = null

    fun attachHud(overlay: NavigationHudOverlay, ctx: Context) {
        hud = overlay
        context = ctx.applicationContext
        elementFinder = ElementFinderEngine(context)
        Log.d(TAG, "HUD + Context attached to NavigationStateMachine")
    }

    fun attachMarker(markerOverlay: ElementMarkerOverlay) {
        marker = markerOverlay
        Log.d(TAG, "ElementMarkerOverlay attached to NavigationStateMachine")
    }

    // [PERMANENT FIX] Do not remove this! Canonical Root Screen Verification.
    internal fun isCurrentScreenRoot(screenName: String, targetPkg: String, targetLabel: String): Boolean {
        val clean = ScreenTitleResolver.stripBadgeNoise(screenName)
        val norm = clean.trim().lowercase()
        val lblNorm = targetLabel.trim().lowercase()
        return norm.isEmpty() ||
               norm == lblNorm ||
               (norm == "settings" && !targetPkg.contains("whatsapp") && !lblNorm.contains("whatsapp")) ||
               norm == "$lblNorm home" ||
               norm == "$lblNorm-home" ||
               norm == "settings home" ||
               norm == "settings-home" ||
               norm == "home" ||
               norm.endsWith("-home") ||
               ((norm == "chats" || norm.startsWith("chats")) && (targetPkg.contains("whatsapp") || lblNorm.contains("whatsapp"))) ||
               (norm == "search" && (lblNorm == "settings" || targetPkg.contains("settings")))
    }

    fun startNavigation(
        resolvedPath: List<String>,
        pkgTarget: String,
        exactTask: String = "",
        isReroute: Boolean = false,
        source: String = "local"
    ) {
        if (resolvedPath.isEmpty()) return
        postToMain {
            wasRerouted = isReroute
            pathSource = source.lowercase().trim()
            targetPackage = pkgTarget.trim().lowercase()
            connectedTargetPackages = setOf(targetPackage)
            // Pre-resolve the human-readable label for this target package
            targetAppLabel = MainFilter.resolveAppLabel(context, pkgTarget).also {
                Log.i(TAG, "Target resolved: pkg='$targetPackage' label='$it'")
            }
            initialTask = exactTask
            pathSteps = resolvedPath.toMutableList()
            lastCorrectStep = null
            isNavigating = true
            isCurrentScreenUnrecognized = false
            lastWrongTapText = null
            lastWrongTapTimeMs = 0L

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
                        subTag            = "first-run-home",
                        hudState          = NavigationHudOverlay.HudState.ON_TRACK
                    )
                    hud.show()
                    // Don't start the main navigation yet — wait for the next onScreenChanged
                    // which will detect the launcher and fire the real start sequence.
                    return@postToMain
                }
            }

            Log.i(TAG, "Navigation STARTED — ${pathSteps.size} steps toward '$targetAppLabel' ($targetPackage)")

            val launcherPkg = getDefaultLauncherPackage()

            // [PERMANENT FIX] Do not remove this! Live Foreground Application & Page Title Resolution:
            // Query the top-most focused or active TYPE_APPLICATION window directly from the OS.
            // Full-screen application activities (MainActivity, Settings, WhatsApp) are TYPE_APPLICATION.
            // Floating overlays (bubble, HUD, search dialog) are TYPE_APPLICATION_OVERLAY / TYPE_ACCESSIBILITY_OVERLAY.
            // Never trust stale cached lastKnownActivePackage or activeScreenName when starting navigation.
            var livePkg: String? = null
            var liveTitle: String? = null

            val service = UiTreeAccessibilityService.instance
            if (service != null) {
                val resolved = AppWindowResolver.getUnderlyingAppRoot(service)
                if (resolved != null) {
                    livePkg = resolved.packageName?.toString()
                    liveTitle = findPageTitle(resolved, targetAppLabel, livePkg ?: "")
                    try { resolved.recycle() } catch (_: Exception) {}
                }
            }

            val activePkg = livePkg ?: lastKnownActivePackage
            if (activePkg.isNotBlank()) {
                lastKnownActivePackage = activePkg
            }

            val isOnLauncher = launcherPkg != null && activePkg.startsWith(launcherPkg)
            val alreadyInApp = activePkg.isNotEmpty() && isTargetApp(activePkg, null)

            // [PERMANENT FIX] Do not remove this! Stale Cross-App Screen Title Sanitization.
            // Never allow a screen title from a previously opened app (e.g. "Telegram") to leak into
            // navigation for targetPackage (e.g. "com.whatsapp"). If liveTitle is null or from another package,
            // default activeScreenName authoritatively to the target app's canonical root screen.
            val defaultRootTitle = if (targetPackage.contains("whatsapp")) "Chats"
                                   else if (targetPackage.contains("settings")) "Settings"
                                   else targetAppLabel

            if (livePkg == targetPackage && !liveTitle.isNullOrBlank()) {
                activeScreenName = liveTitle
            } else if (alreadyInApp) {
                activeScreenName = liveTitle?.takeIf { it.isNotBlank() } ?: defaultRootTitle
            } else {
                activeScreenName = defaultRootTitle
            }

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
                    val isRoot = isCurrentScreenRoot(activeScreenName, targetPackage, targetAppLabel)
                    val nearest = if (!isRoot && screenNorm.isNotEmpty()) findNearestStep(screenNorm) else null
                    val nearestIdx = nearest?.first ?: -1

                    var candidateIdx = when {
                        // [PERMANENT FIX] Do not remove this! Root Screen Absolute Priority: If currently on root screen, never fast-forward into internal sub-page steps!
                        isRoot -> {
                            var idx = 0
                            while (idx < pathSteps.size) {
                                val sn = pathSteps[idx].trim().lowercase()
                                val isHome    = sn == "home" || (launcherPkg != null && launcherPkg.contains(sn))
                                val isAppRoot = appRootFuzzyMatch(sn, idx)
                                if (!isHome && !isAppRoot) break
                                idx++
                            }
                            idx.coerceAtMost(pathSteps.size - 1)
                        }
                        // Screen matches a known step — start from the step AFTER it
                        nearestIdx >= 0 -> {
                            Log.i(TAG, "Fast-forward: screen='$activeScreenName' matches step[$nearestIdx]='${nearest!!.second}' — starting from ${nearestIdx + 1}")
                            (nearestIdx + 1).coerceAtMost(pathSteps.size - 1)
                        }
                        // [PERMANENT FIX] Do not remove this! Dynamic Live Tree Sub-Screen Unwinding & Position-Aware Anchoring.
                        // When user is in the target app on a leaf sub-page (e.g. "About phone" or deep sub-menu),
                        // first check if any step in pathSteps is ALREADY physically visible on the current screen (e.g. "Dark mode" on Display & brightness).
                        // If an item is already on screen, start directly on it without prepending BACK!
                        else -> {
                            val visibleStepIdx = if (::elementFinder.isInitialized) {
                                pathSteps.indexOfFirst { step ->
                                    val b = elementFinder.findElementBoundsInLiveTree(step)
                                    b != null
                                }
                            } else -1

                            if (visibleStepIdx >= 0) {
                                Log.i(TAG, "Live screen anchor: step[$visibleStepIdx]='${pathSteps[visibleStepIdx]}' is already visible on screen '$activeScreenName' — starting directly at $visibleStepIdx without BACK")
                                visibleStepIdx
                            } else {
                                if (!pathSteps.firstOrNull().equals("BACK", ignoreCase = true)) {
                                    pathSteps.add(0, "BACK")
                                }
                                0
                            }
                        }
                    }

                    // ── Reroute sub-page detection ─────────────────────────────
                    // On OEM ROMs (ColorOS, MIUI, etc.), the accessibility screen
                    // name often reports the PARENT page ("Settings") even when the
                    // user is already on a sub-page ("About device").
                    // After the initial fast-forward, check if the candidate step
                    // actually matches the *current visible page title* by scanning
                    // later steps for a fuzzy match against elements on screen.
                    if (candidateIdx < pathSteps.size && ::elementFinder.isInitialized) {
                        val candidateNorm = pathSteps[candidateIdx].trim().lowercase()
                        // Check if candidate step matches the screen title (toolbar)
                        // using the active screen label from the accessibility tree
                        val uiTree = UiTreeAccessibilityService.instance
                        // [PERMANENT FIX] Do not remove this! Use getUnderlyingAppRoot to inspect the target app toolbar rather than our own overlay window
                        val rootNode = if (uiTree != null) AppWindowResolver.getUnderlyingAppRoot(uiTree, context.packageName) else null
                        val pageTitle = rootNode?.let { root ->
                            // Look for the page title in the toolbar
                            val title = findPageTitle(root, targetAppLabel, targetPackage)
                            try { root.recycle() } catch (_: Exception) {}
                            title
                        }
                        if (pageTitle != null) {
                            val titleNorm = pageTitle.trim().lowercase()
                            if (fuzzyMatch(titleNorm, candidateNorm)) {
                                // User is already ON this page! Skip it.
                                Log.i(TAG, "Reroute fix: page title '$pageTitle' matches candidate step '$candidateNorm' — advancing past it")
                                candidateIdx = (candidateIdx + 1).coerceAtMost(pathSteps.size - 1)
                            }
                        }
                    }

                    startIdx = candidateIdx
                    if (pathSteps.getOrNull(startIdx).equals("BACK", ignoreCase = true)) {
                        startHeader      = "RETURN TO ROOT"
                        startInstruction = "Press Back to return to $targetAppLabel"
                    } else {
                        startHeader      = formatStepHeader("STEP ${startIdx + 1} OF ${pathSteps.size}")
                        startInstruction = "Look for '${pathSteps[startIdx]}'"
                    }
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

            // [PERMANENT FIX] Do not remove this! Build the Stateful Execution Tree from pathSteps.
            // Converts linear path into a bidirectional chain of NavExecutionNode objects.
            executionTree.clear()
            for (i in pathSteps.indices) {
                val screen = when {
                    i == 0 -> if (pathSteps[0].equals("Home", ignoreCase = true)) "Home" else targetAppLabel
                    i == 1 && pathSteps[0].equals("Home", ignoreCase = true) -> targetAppLabel
                    targetPackage == "com.whatsapp" && (pathSteps[i - 1].equals("WhatsApp", ignoreCase = true) || pathSteps[i - 1].equals("Home", ignoreCase = true)) -> "Chats"
                    targetPackage == "com.whatsapp" && pathSteps[i - 1].equals("More options", ignoreCase = true) -> {
                        if (i >= 2 && !pathSteps[i - 2].equals("Chats", ignoreCase = true) && !pathSteps[i - 2].equals("WhatsApp", ignoreCase = true)) {
                            "Chat Menu"
                        } else {
                            "Menu"
                        }
                    }
                    else -> pathSteps[i - 1]
                }
                val target = pathSteps[i]
                val nextScreen = pathSteps.getOrNull(i)
                val parent = if (i > 0) i - 1 else null
                executionTree.add(
                    NavExecutionNode(
                        stepIndex = i,
                        screenTitle = screen,
                        targetElement = target,
                        nextScreenTitle = nextScreen,
                        parentIndex = parent
                    )
                )
            }

            // [PERMANENT FIX] Do not remove this! Seed active execution path into SQLite for 0-DB cold start learning
            stateMachineScope.launch(Dispatchers.IO) {
                if (::context.isInitialized && targetPackage.isNotBlank()) {
                    NavGraphDatabase.getInstance(context).seedRoute(targetPackage, targetAppLabel, pathSteps)
                }
            }

            currentTrackerState = when {
                alreadyInApp && startIdx < executionTree.size -> TrackerState.OnRoute(executionTree[startIdx])
                isOnLauncher -> TrackerState.OffApp(targetAppLabel, "Home")
                else -> TrackerState.OffApp(targetAppLabel, activePkg.substringAfterLast('.'))
            }

            Log.i(TAG, "Navigation READY: startIdx=$startIdx currStep='$currStep' nextStep='$nextStep'")
            FlightRecorder.record("NAV_READY", "Navigation started at step $startIdx: '$currStep'", "TargetApp: '$targetAppLabel' ($targetPackage), Source: $pathSource, TotalSteps: ${pathSteps.size}")

            hud.updateHud(
                stepHeader        = startHeader,
                actionInstruction = startInstruction,
                breadcrumb        = buildBreadcrumb(),
                contextText       = if (currStep.equals("BACK", ignoreCase = true)) "Currently on '$activeScreenName' — return to root first"
                                    else if (alreadyInApp) "Next: ${nextStep ?: "Done"}" else null,
                subTag            = if (startIdx > 0) "smart-fast-forward" else "start",
                hudState          = if (alreadyInApp) NavigationHudOverlay.HudState.ON_TRACK
                                    else NavigationHudOverlay.HudState.OFF_TRACK
            )
            hud.show()

            // [PERMANENT FIX] Stream navigation startup decision to Dashboard
            DebugTelemetryClient.emit(
                type = "STEP_ADVANCED",
                tag = "NavigationEngine",
                title = "Navigation Started: $startInstruction",
                description = "Path: ${pathSteps.joinToString(" -> ")}",
                currentStep = currStep,
                stepIndex = startIdx,
                totalSteps = pathSteps.size,
                packageName = targetPackage,
                screenTitle = activeScreenName
            )

            if (alreadyInApp) {
                triggerSearchPipeline(currStep, activePkg, activeScreenName)
            }
        }
    }


    fun stopNavigation() {
        postToMain {
            // [PERMANENT FIX] Always execute cleanup to ensure HUD and marker hide, even if we already reached the destination (isNavigating=false)
            MarkerScout.shutdown(isPermanentStop = true)
            isNavigating = false
            currentTrackerState = null
            executionTree.clear()
            targetPackage = ""
            connectedTargetPackages = emptySet()
            targetAppLabel = ""
            pathSteps = mutableListOf()
            currentIndex = 0
            prevStep = null
            currStep = ""
            nextStep = null
            lastCorrectStep = null
            activeScreenName = ""
            isCurrentScreenUnrecognized = false
            lastWrongTapText = null
            lastWrongTapTimeMs = 0L
            marker?.hideMarker()
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
    private var lastWrongTapText: String? = null
    private var lastWrongTapTimeMs: Long = 0L

    fun onElementTapped(tappedLabel: String, fromPackage: String) {
        if (!isNavigating) return

        val tapNorm  = tappedLabel.trim().lowercase()
        val currNorm = currStep.trim().lowercase()
        val nextNorm = nextStep?.trim()?.lowercase()

        // Ignore taps from our own overlay or system UI (except navigation Back button)
        if (fromPackage == context.packageName) return
        val isSystemNavBack = fromPackage.startsWith("com.android.systemui") && (tapNorm == "back" || tapNorm.contains("back"))
        if (fromPackage.startsWith("com.android.systemui") && !isSystemNavBack) return

        if (fuzzyMatch(tapNorm, currNorm, MatchContext.CURRENT_STEP_TAP)) {
            // Tap matches current step!
            lastWrongTapText = null
            lastWrongTapTimeMs = 0L
            Log.i(TAG, "onElementTapped: tapped='$tappedLabel' matches currStep='$currStep' — advancing")

            postToMain {
                if (!isNavigating) return@postToMain
                if (!fuzzyMatch(currStep.trim().lowercase(), currNorm, MatchContext.CURRENT_STEP_TAP)) return@postToMain
                lastCorrectStep = currStep
                advanceStep(currentIndex)
            }
            return
        } else {
            // [PERMANENT FIX] Do not remove this! Universal Ambiguous Control Guard & Strict Diverged State Lock.
            // 1. If currently diverged (isDiverged), skip-ahead matching is STRICTLY FORBIDDEN to prevent actions
            //    on wrong sub-screens from falsely resuming navigation (e.g. tapping 3-dots in Alice's chat matching Rohit's 3-dots).
            // 2. Generic ambiguous control words ("more options", "more", "search", "menu", "settings", "back", "add", "done")
            //    exist on multiple screens. They can NEVER skip-advance unless the active screen matches the
            //    screen that specifically owns that action in executionTree.
            val isDiverged = currentTrackerState is TrackerState.Diverged ||
                             lastWrongTapText != null ||
                             isCurrentScreenUnrecognized

            val ambiguousGenericControls = setOf(
                "more options", "more", "search", "menu", "settings", "back", "add", "new", "done", "edit", "close", "cancel"
            )

            var foundFutureIdx = -1
            if (!isDiverged) {
                for (i in currentIndex + 1 until pathSteps.size) {
                    val futureStepNorm = pathSteps[i].trim().lowercase()
                    if (fuzzyMatch(tapNorm, futureStepNorm, MatchContext.FUTURE_STEP_SKIP)) {
                        val isAmbiguous = ambiguousGenericControls.contains(futureStepNorm)
                        if (isAmbiguous) {
                            val expectedScreen = executionTree.getOrNull(i)?.screenTitle?.trim()?.lowercase()
                            val currentScreenNorm = activeScreenName.trim().lowercase()
                            val screenMatches = expectedScreen != null &&
                                    currentScreenNorm.isNotBlank() &&
                                    fuzzyMatch(currentScreenNorm, expectedScreen, MatchContext.SCREEN_TITLE)
                            if (!screenMatches) {
                                Log.w(TAG, "onElementTapped: blocked future-skip on ambiguous control '$tappedLabel' to step $i because active screen '$activeScreenName' != expected '$expectedScreen'")
                                continue
                            }
                        }
                        foundFutureIdx = i
                        break
                    }
                }
            }

            if (foundFutureIdx != -1) {
                lastWrongTapText = null
                lastWrongTapTimeMs = 0L
                Log.i(TAG, "onElementTapped: tapped='$tappedLabel' matches future step '${pathSteps[foundFutureIdx]}' (idx $foundFutureIdx) — skip-advancing")

                postToMain {
                    if (!isNavigating) return@postToMain
                    currentIndex = foundFutureIdx
                    prevStep = if (currentIndex > 0) pathSteps[currentIndex - 1] else null
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    lastCorrectStep = currStep
                    advanceStep(currentIndex)
                }
                return
            }
            // [PERMANENT FIX] Do not remove this! Exact and word-boundary matching for ignored system/navigation labels.
            // Using loose substring `tapNorm.contains(it)` caused menu items like "Home screen, Lock screen & style"
            // or "More settings" to match "home" / "more" / "settings", falsely treating real settings choices as system buttons!
            val ignoredExactLabels = setOf(
                "back", "home", "overview", "navigate up", "more options",
                "close", "search", "submit", "cancel", "ok", "done", "yes", "no",
                "on", "off", "switch", "checkbox", "radio", "null", "nil"
            )
            val isNavigationTap = ignoredExactLabels.contains(tapNorm) ||
                                  tapNorm.startsWith("back") ||
                                  tapNorm.endsWith("back") ||
                                  tapNorm.contains("navigate up") ||
                                  tapNorm == "expand" || tapNorm == "collapse" ||
                                  tapNorm == "null" || tapNorm == "nil"

            // [PERMANENT FIX] Do not remove this! If the user tapped a navigation button (Back, Navigate Up, Home)
            // or if an empty icon produced 'null'/'nil', do NOT treat it as a wrong tap! Return immediately.
            val isBackTap = tapNorm == "back" || tapNorm.contains("navigate up")
            if (isBackTap) {
                if (currStep.equals("BACK", ignoreCase = true)) {
                    Log.i(TAG, "onElementTapped: user tapped Back button for expected 'BACK' step")
                    return
                }
                val state = currentTrackerState
                if (state is TrackerState.Diverged) {
                    val newStack = state.divergedStack.dropLast(1)
                    if (newStack.isEmpty()) {
                        Log.i(TAG, "onElementTapped: user pressed Back while diverged. Unwound fully to node '${state.originNode.screenTitle}'")
                        FlightRecorder.record("BRANCH_UNWOUND", "User pressed Back while diverged. Fully unwound to '${state.originNode.screenTitle}'", "Target: '${state.originNode.targetElement}'")
                        currentTrackerState = TrackerState.OnRoute(state.originNode)
                        lastWrongTapText = null
                        lastWrongTapTimeMs = 0L
                        isCurrentScreenUnrecognized = false
                        MarkerScout.shutdown(isPermanentStop = false)
                        postToMain {
                            hud.updateHud(
                                stepHeader        = formatStepHeader("STEP ${state.originNode.stepIndex + 1} OF ${pathSteps.size}"),
                                actionInstruction = "Look for '${state.originNode.targetElement}'",
                                breadcrumb        = buildBreadcrumb(),
                                contextText       = "Returned to ${state.originNode.screenTitle}",
                                subTag            = "unwind-back",
                                hudState          = NavigationHudOverlay.HudState.ON_TRACK
                            )
                            triggerSearchPipeline(state.originNode.targetElement, lastKnownActivePackage, state.originNode.screenTitle)
                        }
                    } else {
                        Log.i(TAG, "onElementTapped: user pressed Back while diverged. Remaining stack size: ${newStack.size}")
                        FlightRecorder.record("BRANCH_UNWOUND", "User pressed Back while diverged. Remaining depth: ${newStack.size} ('${newStack.last()}')", "Origin: '${state.originNode.screenTitle}'")
                        val prevScreen = newStack.last().trim()
                        val sanitizedPrevScreen = if (prevScreen.isEmpty() || prevScreen.equals("null", ignoreCase = true) || prevScreen.equals("nil", ignoreCase = true)) null else prevScreen
                        // [PERMANENT FIX] Update lastWrongTapText to current level so HUD title reflects current screen
                        lastWrongTapText = sanitizedPrevScreen
                        currentTrackerState = state.copy(
                            currentScreenTitle = newStack.last(),
                            divergedStack = newStack,
                            backStepsRequired = newStack.size
                        )
                        updateDivergedRecovery(state.originNode.screenTitle, newStack.last(), state.originNode.targetElement, newStack.size)
                    }
                    return
                }
                return
            }

            if (isNavigationTap || tappedLabel.isBlank() || tapNorm == "null" || tapNorm == "nil") {
                Log.d(TAG, "onElementTapped: ignored navigation/system tap '$tappedLabel'")
                return
            }

            // If they tapped a different interactive label (not a system nav button), track it immediately
            val tapTrimmed = tappedLabel.trim()
            val isInvalidTap = tapTrimmed.isEmpty() ||
                               tapTrimmed.equals("null", ignoreCase = true) ||
                               tapTrimmed.equals("nil", ignoreCase = true) ||
                               tapTrimmed.length <= 2
            if (!isInvalidTap) {
                lastWrongTapText = tapTrimmed
                lastWrongTapTimeMs = System.currentTimeMillis()
                Log.w(TAG, "onElementTapped: user tapped '$tapTrimmed' (expected '$currStep')")
                DebugTelemetryClient.sendWrongPath(tapTrimmed, currStep)
            }

            // [PERMANENT FIX] Do not remove this! Cancel in-flight scout search and hide marker immediately on wrong tap.
            // Previously, checking if currStep was still visible in the tree caused the green marker to linger
            // and freeze on screen across activity transitions, drawing green boxes on unrelated elements (e.g. "Review settings").
            // When the user taps a different menu item, guidance marker MUST be hidden immediately.
            MarkerScout.shutdown(isPermanentStop = false)
            postToMain {
                if (isNavigating) {
                    marker?.hideMarker()
                }
            }

            if (tappedLabel.length > 2) {
                // [PERMANENT FIX] Do not remove this! Set isCurrentScreenUnrecognized = true immediately so that
                // subsequent scroll ticks / content change events don't overwrite WRONG PATH with false scroll directives.
                isCurrentScreenUnrecognized = true
                val state = currentTrackerState
                val (originNode, newStack) = if (state is TrackerState.Diverged) {
                    Pair(state.originNode, state.divergedStack + tappedLabel)
                } else {
                    val origin = (state as? TrackerState.OnRoute)?.node
                        ?: executionTree.getOrNull(currentIndex)
                        ?: NavExecutionNode(currentIndex, activeScreenName, currStep, nextStep, if (currentIndex > 0) currentIndex - 1 else null)
                    Pair(origin, listOf(tappedLabel))
                }

                FlightRecorder.record("BRANCH_DIVERGED", "Tapped '$tappedLabel' away from expected '${originNode.targetElement}'", "Diverged depth: ${newStack.size}, Stack: $newStack, Origin: '${originNode.screenTitle}'")
                currentTrackerState = TrackerState.Diverged(
                    originNode = originNode,
                    currentScreenTitle = tappedLabel,
                    divergedStack = newStack,
                    backStepsRequired = newStack.size,
                    nextPlannedTarget = originNode.targetElement
                )

                postToMain {
                    if (!isNavigating) return@postToMain
                    marker?.hideMarker()
                }
                updateDivergedRecovery(originNode.screenTitle, tappedLabel, originNode.targetElement, newStack.size)
            }
            return
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

        postToMain {
            if (!isNavigating) return@postToMain

            val launcherPkg = getDefaultLauncherPackage()
            val isLauncherEvent = (launcherPkg != null && (activePackage.startsWith(launcherPkg) || activePackage == launcherPkg)) ||
                                  activePackage.contains("launcher", ignoreCase = true) ||
                                  activePackage == "com.android.launcher"

            var livePageTitle: String? = null
            if (!isLauncherEvent) {
                val uiTree = UiTreeAccessibilityService.instance
                if (uiTree != null) {
                    livePageTitle = AppWindowResolver.getUnderlyingAppRoot(uiTree, "com.example.floatingassistant")?.let { root ->
                        val title = findPageTitle(root, activeAppLabel, activePackage)
                        try { root.recycle() } catch (_: Exception) {}
                        title
                    }
                }
            }

            val oldScreenName = activeScreenName
            activeScreenName = if (isLauncherEvent) {
                "Home"
            } else {
                livePageTitle?.takeIf { it.isNotBlank() } 
                    ?: currentScreenName.replace(Regex("-Back$", RegexOption.IGNORE_CASE), "").trim()
            }

            // [PERMANENT FIX] Do not remove this!
            // If we are still in the exact same package and exact same screen,
            // this isn't a real navigation change. It's just a dynamic UI update.
            // We delegate it to onContentScrolled() to prevent HUD flickering and log spam.
            if (activePackage == lastKnownActivePackage && activeScreenName == oldScreenName && oldScreenName.isNotEmpty()) {
                onContentScrolled()
                return@postToMain
            }

            lastKnownActivePackage = activePackage

            // Screen actually changed — previous marker position is invalid
            marker?.hideMarker()

            val currStepIsHome = currStep.trim().equals("home", ignoreCase = true)
            // Use the PM label if available, otherwise fall back to package-level matching
            val inTargetApp = isTargetApp(activePackage, activeAppLabel.ifBlank { null })

            Log.d(TAG, "Screen: pkg='$activePackage' label='$activeAppLabel' screen='$activeScreenName' | currStep='$currStep' inTarget=$inTargetApp isLauncher=$isLauncherEvent")

            // [PERMANENT FIX] Stream screen change to Dashboard for Live Tree Location Flagging
            DebugTelemetryClient.emit(
                type = "SCREEN_CHANGED",
                tag = "NavigationEngine",
                title = "Screen: $activeScreenName",
                description = "Package: $activePackage | Step: $currStep (${currentIndex + 1}/${pathSteps.size})",
                packageName = activePackage,
                screenTitle = activeScreenName,
                currentStep = currStep,
                stepIndex = currentIndex,
                totalSteps = pathSteps.size
            )

            // ── Fast-forward or Unwind: already in the target app before navigation tapped ──
            if (currentIndex == 0 && inTargetApp && !isLauncherEvent) {
                val screenNormEarly = activeScreenName.trim().lowercase()
                val isRoot = isCurrentScreenRoot(activeScreenName, targetPackage, targetAppLabel)
                val nearestEarly = if (!isRoot && screenNormEarly.isNotEmpty()) findNearestStep(screenNormEarly) else null
                val nearestIdxEarly = nearestEarly?.first ?: -1

                if (isRoot) {
                    var startIdx = 0
                    while (startIdx < pathSteps.size) {
                        val stepNorm = pathSteps[startIdx].trim().lowercase()
                        val isHome = stepNorm == "home" || (launcherPkg != null && launcherPkg.contains(stepNorm))
                        val isAppRoot = appRootFuzzyMatch(stepNorm, startIdx)
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
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = "Look for '$currStep'",
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Current: $activeScreenName",
                            subTag            = "dynamic-fast-forward",
                            hudState          = NavigationHudOverlay.HudState.ON_TRACK
                        )
                        triggerSearchPipeline(currStep, activePackage, activeScreenName)
                        return@postToMain
                    }
                } else if (nearestIdxEarly >= 0) {
                    val targetIdx = (nearestIdxEarly + 1).coerceAtMost(pathSteps.size - 1)
                    if (targetIdx > currentIndex) {
                        currentIndex = targetIdx
                        prevStep = pathSteps[nearestIdxEarly]
                        currStep = pathSteps[currentIndex]
                        nextStep = pathSteps.getOrNull(currentIndex + 1)
                        lastCorrectStep = prevStep
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = "Look for '$currStep'",
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Current: $activeScreenName",
                            subTag            = "dynamic-fast-forward",
                            hudState          = NavigationHudOverlay.HudState.ON_TRACK
                        )
                        triggerSearchPipeline(currStep, activePackage, activeScreenName)
                        return@postToMain
                    }
                } else {
                    // [PERMANENT FIX] Do not remove this! Dynamic Live Tree Sub-Screen Unwinding on Cold Start.
                    // User opened target app from recents or background into a deep sub-screen (e.g. "About phone").
                    // Prepend "BACK" to unwind back to root first!
                    if (!pathSteps.firstOrNull().equals("BACK", ignoreCase = true)) {
                        pathSteps.add(0, "BACK")
                    }
                    currentIndex = 0
                    currStep = "BACK"
                    nextStep = pathSteps.getOrNull(1)
                    hud.updateHud(
                        stepHeader        = "RETURN TO ROOT",
                        actionInstruction = "Press Back to return to $targetAppLabel",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Currently on '$activeScreenName' — return to root first",
                        subTag            = "subscreen-unwind-back",
                        hudState          = NavigationHudOverlay.HudState.ON_TRACK
                    )
                    triggerSearchPipeline("BACK", activePackage, activeScreenName)
                    return@postToMain
                }
            }

            // ── Clear wrong tap state if we returned to a known path ───────────
            val screenNorm = activeScreenName.trim().lowercase()
            val currNorm   = currStep.trim().lowercase()
            val nextNorm   = nextStep?.trim()?.lowercase()

            // [PERMANENT FIX] Do not remove this! Canonical Root Screen Verification in onScreenChanged.
            // Calls isCurrentScreenRoot to authoritatively recognize app root screens across all apps,
            // including WhatsApp ("Chats" / "WhatsApp"), Settings ("Settings" / "Settings Homepage"), etc.
            val isRootScreen = isCurrentScreenRoot(activeScreenName, targetPackage, targetAppLabel) ||
                               activeScreenName.equals(targetAppLabel, ignoreCase = true) ||
                               activeScreenName.equals("Settings", ignoreCase = true) ||
                               screenNorm == "${targetAppLabel.lowercase()} home" ||
                               screenNorm == "${targetAppLabel.lowercase()}-home" ||
                               screenNorm == "settings home" ||
                               screenNorm == "settings-home" ||
                               screenNorm == "home" ||
                               screenNorm.endsWith("-home")

            // [PERMANENT FIX] Do not remove this! In-page search box noise guard.
            // When scrolling on root screens (like Settings), an inline search bar might transiently report "Search".
            // If we are in the target app and no wrong tap has occurred, treat "Search" on root as root screen.
            val isSearchNoiseOnRoot = activeScreenName.equals("Search", ignoreCase = true) &&
                                      inTargetApp &&
                                      lastWrongTapText == null &&
                                      (targetAppLabel.equals("Settings", ignoreCase = true) || currentIndex == 0)

            val isEffectiveRootScreen = isRootScreen || isSearchNoiseOnRoot
            val nearest = findNearestStep(screenNorm)
            val nearestIdx = nearest?.first ?: -1
            val nearestName = nearest?.second

            val originTitle = (currentTrackerState as? TrackerState.Diverged)?.originNode?.screenTitle?.trim()?.lowercase()
            val isOriginRoot = originTitle == "settings" || (originTitle != null && originTitle == targetAppLabel.trim().lowercase())
            // [PERMANENT FIX] Do not remove this! For root settings screens, returnedToOrigin requires true root screen
            // verification (isEffectiveRootScreen) or exact match. Loose fuzzyMatch(screenNorm, "settings") caused
            // sub-pages with "settings" in their title (e.g. "Notifications & Quick Settings", "Privacy settings")
            // to falsely claim returnedToOrigin = true and wipe out diverged wrong-path recovery!
            val returnedToOrigin = originTitle != null && if (isOriginRoot) {
                isEffectiveRootScreen || screenNorm == originTitle
            } else {
                screenNorm == originTitle || (fuzzyMatch(screenNorm, originTitle) && !screenNorm.contains("settings"))
            }

            val isOnKnownPath = (isLauncherEvent && currStepIsHome) ||
                                (appRootFuzzyMatch(currNorm, currentIndex) && inTargetApp) ||
                                fuzzyMatch(screenNorm, currNorm) ||
                                (nextNorm != null && fuzzyMatch(screenNorm, nextNorm)) ||
                                (inTargetApp && isEffectiveRootScreen) ||
                                returnedToOrigin

            if (isOnKnownPath) {
                lastWrongTapText = null
                lastWrongTapTimeMs = 0L
                isCurrentScreenUnrecognized = false
                if (currentTrackerState is TrackerState.Diverged) {
                    val origin = (currentTrackerState as TrackerState.Diverged).originNode
                    currentTrackerState = TrackerState.OnRoute(origin)
                    Log.i(TAG, "onScreenChanged: returned to origin node '${origin.screenTitle}', restored OnRoute state")
                    FlightRecorder.record("RESTORE_ROUTE", "Returned to origin node '${origin.screenTitle}' - restored OnRoute", "Target: '${origin.targetElement}'")
                } else if (currentTrackerState is TrackerState.OffApp) {
                    // [PERMANENT FIX] Do not remove this! Restore OnRoute when user re-enters target app on known path screen.
                    val activeNode = executionTree.getOrNull(currentIndex)
                        ?: NavExecutionNode(currentIndex, activeScreenName, currStep, currStep, 0)
                    currentTrackerState = TrackerState.OnRoute(activeNode)
                    Log.i(TAG, "onScreenChanged: returned from OffApp to target app on '${activeScreenName}', restored OnRoute state")
                    FlightRecorder.record("RESTORE_ROUTE_OFFAPP", "Returned from OffApp to target app on '${activeScreenName}'", "Target: '$currStep'")
                }
            }

            val divState = currentTrackerState as? TrackerState.Diverged
            if (divState != null && divState.divergedStack.size > 1) {
                val parentScreen = divState.divergedStack[divState.divergedStack.size - 2]
                if (fuzzyMatch(screenNorm, parentScreen.trim().lowercase())) {
                    val newStack = divState.divergedStack.dropLast(1)
                    // [PERMANENT FIX] Update lastWrongTapText to current level so HUD title reflects current screen
                    lastWrongTapText = newStack.last()
                    currentTrackerState = divState.copy(
                        currentScreenTitle = newStack.last(),
                        divergedStack = newStack,
                        backStepsRequired = newStack.size
                    )
                    Log.i(TAG, "onScreenChanged: gesture back unwound 1 level to '${newStack.last()}' (${newStack.size} remaining)")
                    FlightRecorder.record("BRANCH_UNWOUND_GESTURE", "Gesture back unwound 1 level to '${newStack.last()}' (${newStack.size} remaining)", "Origin: '${divState.originNode.screenTitle}'")
                }
            }

            // ── Off-app / Wrong-path states ────────────────────────────────────
            // [PERMANENT FIX] Do not remove this! Persistent wrong-tap tracking across screen transitions.
            // Wrong tap guidance must persist until the user returns to a known path screen (isOnKnownPath).
            // Expiring after 5 seconds previously caused the HUD to forget the wrong tap and falsely instruct scrolling.
            if (lastWrongTapText != null && !isOnKnownPath) {
                val wrongTap = lastWrongTapText?.trim()
                val hasTap = !wrongTap.isNullOrEmpty() && !wrongTap.equals("null", ignoreCase = true) && !wrongTap.equals("nil", ignoreCase = true)
                if (!hasTap) {
                    lastWrongTapText = null
                } else {
                    isCurrentScreenUnrecognized = true
                    val triggerReroute = { onRerouteRequested?.invoke(targetPackage, initialTask) ?: Unit }
                    val currentDiv = currentTrackerState as? TrackerState.Diverged
                    val originNodeTitle = currentDiv?.originNode?.screenTitle ?: targetAppLabel
                    val targetName = currentDiv?.originNode?.targetElement ?: currStep
                    val steps = currentDiv?.backStepsRequired ?: 1
                    val (backAction, ctx) = formatRecoveryGuidance(originNodeTitle, targetName, steps)
                    hud.updateHud(
                        stepHeader        = "WRONG PATH",
                        actionInstruction = "You tapped '$wrongTap'",
                        breadcrumb        = null, // [PERMANENT FIX] Hide future breadcrumb in wrong-path state to eliminate visual clutter
                        contextText       = backAction,
                        subTag            = "unrecognized-screen-back",
                        onHelpRequested   = triggerReroute,
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                    )
                    // [PERMANENT FIX] Do not remove this! Highlight system navbar or toolbar Back button on wrong tap
                    highlightBackAction()
                    return@postToMain
                }
            }

            if (isLauncherEvent && !currStepIsHome) {
                marker?.hideMarker()
                MarkerScout.shutdown(isPermanentStop = false)
                currentTrackerState = TrackerState.OffApp(expectedApp = targetAppLabel, currentApp = activePackage)
                hud.updateHud(
                    stepHeader        = "ON HOME",
                    actionInstruction = "Open $targetAppLabel",
                    breadcrumb        = buildBreadcrumb(),
                    contextText       = "Expected: $currStep",
                    subTag            = "on-home-diverged",
                    hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                )
                return@postToMain
            }

            if (!currStepIsHome && !inTargetApp && !isLauncherEvent) {
                marker?.hideMarker()
                MarkerScout.shutdown(isPermanentStop = false)
                currentTrackerState = TrackerState.OffApp(expectedApp = targetAppLabel, currentApp = activePackage)
                isCurrentScreenUnrecognized = true
                val actualName = activeAppLabel.ifBlank { activeScreenName }
                hud.updateHud(
                    stepHeader        = "WRONG APP",
                    actionInstruction = "Open $targetAppLabel or return to Home",
                    breadcrumb        = buildBreadcrumb(),
                    contextText       = "Current: $actualName | Expected: $targetAppLabel",
                    subTag            = "pkg-mismatch",
                    hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                )
                return@postToMain
            }

            // [PERMANENT FIX] Do not remove this! Unwinding "BACK" step fulfillment upon returning to root or route screen.
            if (currStep.equals("BACK", ignoreCase = true) && inTargetApp && (isEffectiveRootScreen || nearestIdx >= 0)) {
                Log.i(TAG, "Unwinding BACK step fulfilled: landed on '${nearest?.second ?: activeScreenName}'")
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // ── On-app screen matching ─────────────────────────────────────────
            // Home step confirmation
            if (isLauncherEvent && currStepIsHome) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            // App-root step: being in the target app is enough to advance
            if (appRootFuzzyMatch(currNorm, currentIndex) && inTargetApp) {
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
                // Determine if this is likely a false positive backward navigation
                // caused by an OEM like ColorOS always reporting the root name ("Settings")
                val isLikelyColorOsQuirk = nearestIdx == 0 && isEffectiveRootScreen

                if (nearestIdx in 0 until (currentIndex - 1) && !isLikelyColorOsQuirk) {
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
                        subTag            = "went-back",
                        onHelpRequested   = { onRerouteRequested?.invoke(targetPackage, initialTask) },
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                    )
                } else {
                    // Screen unrecognized but we're in the right app — hunt for the element
                    val timeSinceAdvance = System.currentTimeMillis() - lastAdvanceTimeMs
                    val justAdvanced = timeSinceAdvance < 3000L
                    // [PERMANENT FIX] Do not remove this! Active Node Lock:
                    // While on a valid route node with no wrong taps, scrolling or dynamic UI updates
                    // must never trigger false wrong path alarms.
                    val isLockedOnRoute = currentTrackerState is TrackerState.OnRoute && lastWrongTapText == null
                    val isUnrecognized = (nearestIdx == -1 && !isEffectiveRootScreen && !justAdvanced && !isLockedOnRoute)
                    FlightRecorder.record("NODE_LOCK_EVAL", "Screen '$activeScreenName' (pkg='$activePackage')", "isLockedOnRoute=$isLockedOnRoute, isUnrecognized=$isUnrecognized, state=$currentTrackerState")
                    isCurrentScreenUnrecognized = isUnrecognized

                    if (isUnrecognized) {
                        val currentDiv = currentTrackerState as? TrackerState.Diverged
                        val originNodeTitle = currentDiv?.originNode?.screenTitle ?: targetAppLabel
                        val targetName = currentDiv?.originNode?.targetElement ?: currStep
                        val steps = currentDiv?.backStepsRequired ?: 1
                        val (backAction, ctx) = formatRecoveryGuidance(originNodeTitle, targetName, steps)
                        hud.updateHud(
                            stepHeader        = "WRONG PATH",
                            actionInstruction = backAction,
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = ctx,
                            subTag            = "unrecognized-screen-back",
                            hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                        )
                        // [PERMANENT FIX] Do not remove this! Highlight system navbar or toolbar Back button on unrecognized screen
                        highlightBackAction()
                    } else {
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = "Look for '$currStep'",
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Current: $activeScreenName",
                            subTag            = "in-app-searching",
                            hudState          = NavigationHudOverlay.HudState.ON_TRACK
                        )
                    }
                    triggerSearchPipeline(currStep, activePackage, activeScreenName, isUnrecognized)
                }
                return@postToMain
            }


            // ── Nearest-step recovery ──────────────────────────────────────────
            // Scan ALL path steps and find which one the current screen is
            // closest to. This gives hyper-specific guidance:
            //   "You're near 'Settings'. Go back to Settings, then tap 'Chat Backup'."
            // rather than the generic "Press Back to Home".

            when {
                // User is ON a step that is behind the current position — they went back
                nearestIdx in 0 until (currentIndex - 1) && nearestName != null -> {
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
                        subTag            = "nearest-step-forward",
                        onHelpRequested   = { onRerouteRequested?.invoke(targetPackage, initialTask) },
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
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
                        stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                        actionInstruction = "Look for '$currStep'",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Jumped ahead to '$nearestName'",
                        subTag            = "nearest-step-jumped",
                        hudState          = NavigationHudOverlay.HudState.ON_TRACK
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
                        subTag            = "off-track-anchor",
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                    )
                }

                // Completely lost — guide to the app entry point
                else -> {
                    hud.updateHud(
                        stepHeader        = "OFF TRACK",
                        actionInstruction = "Open $targetAppLabel to restart",
                        breadcrumb        = buildBreadcrumb(),
                        contextText       = "Current: $activeScreenName | Expected: $currStep",
                        subTag            = "off-track-no-anchor",
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
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

            // [PERMANENT FIX] Do not remove this! Root Screen Sub-Page Exclusion in findNearestStep.
            // If the current screen is the app root screen (e.g. "chats" on WhatsApp, "settings" on Settings),
            // it can ONLY match the initial app entry / root step (idx 0), NEVER internal sub-page steps (idx > 0).
            val isRootScreen = isCurrentScreenRoot(screenNorm, targetPackage, targetAppLabel)
            if (isRootScreen && idx > 0 && !appRootFuzzyMatch(stepNorm, idx)) {
                return@forEachIndexed
            }

            val score = when {
                stepNorm == screenNorm -> 4
                // Whole word containment instead of raw substring to prevent "set" matching "settings"
                Regex("(^|\\s)${Regex.escape(screenNorm)}(\\s|$)").containsMatchIn(stepNorm) ||
                (Regex("(^|\\s)${Regex.escape(stepNorm)}(\\s|$)").containsMatchIn(screenNorm) && (stepNorm != targetAppLabel.lowercase() || screenNorm == stepNorm)) -> 3
                stepNorm.contains(screenNorm) && screenNorm.length >= 5 -> 2
                // [PERMANENT FIX] Do not remove this! Don't let a generic root step like "settings" aggressively match any sub-screen (e.g. "wireless settings-wi-fi")
                screenNorm.contains(stepNorm) && stepNorm.length >= 5 && stepNorm != targetAppLabel.lowercase() -> 2
                else -> {
                    val stepTokens   = stepNorm.split(Regex("[\\s\\-_]+")).filter { it.length > 2 }.toSet()
                    val screenTokens = screenNorm.split(Regex("[\\s\\-_]+")).filter { it.length > 2 }.toSet()
                    
                    // If the step is just the generic app name ("settings"), require it to be the ONLY token or an exact token match
                    // to prevent "wireless settings" from scoring high just because of the shared word "settings".
                    if (stepNorm == targetAppLabel.lowercase() && screenTokens.size > 1 && screenTokens.contains(stepNorm)) {
                        0
                    } else {
                        val shared = stepTokens.intersect(screenTokens).size
                        if (shared > 0) {
                            if (stepTokens.size > 1 && shared < stepTokens.size / 2.0) 0 else 1
                        } else {
                            0
                        }
                    }
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
        lastAdvanceTimeMs = System.currentTimeMillis()

        if (nextStep == null) {
            Log.i(TAG, "COMPLETE — step $stepNum/'$currStep' was the last step")
            isNavigating = false
            val lastNode = executionTree.getOrNull(confirmedIndex)
                ?: NavExecutionNode(confirmedIndex, currStep, currStep, null, if (confirmedIndex > 0) confirmedIndex - 1 else null)
            currentTrackerState = TrackerState.DestinationReached(lastNode)
            // [PERMANENT FIX] Stream destination reached event to Dashboard
            DebugTelemetryClient.emit(
                type = "DESTINATION_REACHED",
                tag = "StateMachine",
                title = "Destination Reached: '$currStep'",
                description = "Verification prompt displayed (YES / NO)",
                currentStep = currStep
            )
            // [PERMANENT FIX] Hide the marker immediately when destination is reached so it doesn't get stuck on the screen forever
            marker?.hideMarker()
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
                        subTag            = "destination-no",
                        hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                    )
                }
            )
            return
        }

        FlightRecorder.record("NAV_ADVANCE", "Advanced step from '$currStep' to '${pathSteps.getOrNull(currentIndex + 1)}'", "Confirmed index: $confirmedIndex")
        prevStep = currStep
        currentIndex++
        if (currentIndex < executionTree.size) {
            currentTrackerState = TrackerState.OnRoute(executionTree[currentIndex])
        }
        isCurrentScreenUnrecognized = false
        lastWrongTapText = null
        lastWrongTapTimeMs = 0L

        // Hide old marker — the previous element's position is stale
        marker?.hideMarker()
        currStep = pathSteps[currentIndex]
        nextStep = pathSteps.getOrNull(currentIndex + 1)

        // [PERMANENT FIX] Stream step advancement to Dashboard
        DebugTelemetryClient.sendStepAdvanced(currentIndex, pathSteps.size, currStep, nextStep)

        hud.updateHud(
            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
            actionInstruction = "Look for '$currStep'",
            breadcrumb        = buildBreadcrumb(),
            contextText       = "Next: ${nextStep ?: "Done"}",
            subTag            = "on-track",
            hudState          = NavigationHudOverlay.HudState.ON_TRACK
        )

        triggerSearchPipeline(currStep, lastKnownActivePackage, activeScreenName)
    }

    fun onContentScrolled() {
        if (!isNavigating || currStep.isEmpty()) return
        MarkerScout.onScreenMotion()
    }

    // ── Dedicated Marker Scout Worker ──────────────────────────────────────────

    /**
     * [PERMANENT FIX] Do not remove this! Dedicated Marker Scout Worker.
     *
     * An independent worker supervised by NavigationStateMachine.
     * Responsibility:
     *   - Assigned a specific target element to track.
     *   - Reacts to screen motion (scrolls, page transitions) on a dedicated coroutine job.
     *   - Inspects the live memory tree and updates visual marker bounds in real-time.
     *   - Discards stale search results when the mission/step advances, preventing race conditions.
     *   - Completely shuts down (cancels active coroutines, clears bounds, hides marker) on stop.
     */
    private object MarkerScout {
        private var scoutJob: Job? = null
        @Volatile var currentTarget: String = ""
        private var lastKnownTargetBounds: Rect? = null
        private var lastKnownTargetStep: String = ""
        private var targetScrolledAbove: Boolean = false

        fun assignMission(
            targetStep: String,
            activePkg: String,
            activeScreen: String,
            isUnrecognizedScreen: Boolean = false,
            delayMs: Long = 400L
        ) {
            if (targetStep != lastKnownTargetStep) {
                lastKnownTargetStep = targetStep
                lastKnownTargetBounds = null
                targetScrolledAbove = false
            }
            currentTarget = targetStep
            // Cancel previous in-flight scout search to prevent race conditions
            scoutJob?.cancel()
            if (!::elementFinder.isInitialized) return

            scoutJob = stateMachineScope.launch(Dispatchers.IO) {
                if (delayMs > 0) {
                    delay(delayMs)
                }
                if (!isNavigating || targetStep != currentTarget) return@launch

                // [PERMANENT FIX] Do not remove this check!
                // If the user left the app during the delay, abort search pipeline and hide marker!
                val inTargetApp = isTargetApp(lastKnownActivePackage)
                val currStepIsHome = currStep.trim().equals("home", ignoreCase = true)
                if (!inTargetApp && !currStepIsHome) {
                    marker?.hideMarker()
                    return@launch
                }

                val result = elementFinder.findElement(targetStep, activePkg, activeScreen, prevStep ?: "")
                currentTargetBounds = result.bounds

                if (result.found) {
                    if (result.bounds != null) {
                        lastKnownTargetBounds = Rect(result.bounds)
                    }
                    postToMain {
                        if (!isNavigating || targetStep != currentTarget) return@postToMain
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = "Tap '$targetStep'",
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Found on screen",
                            subTag            = "target-found",
                            hudState          = NavigationHudOverlay.HudState.FOUND
                        )
                        FlightRecorder.record("SCOUT_FOUND", "Target '$targetStep' located on screen at ${result.bounds}", "Screen: '$activeScreen'")
                        // Show visual marker on the found element
                        if (result.bounds != null) {
                            marker?.showMarker(result.bounds, isSystemNav = result.isSystemNav)
                            // [PERMANENT FIX] Stream target located coordinates to Dashboard
                            DebugTelemetryClient.sendElementFound(targetStep, result.bounds)
                        }
                    }
                    return@launch
                }

                // [PERMANENT FIX] Do not remove this! Multi-Source Directional Scroll Tracker:
                // 1. Element Order & Container Boundary Intelligence:
                //    Inspects native scroll container boundaries (cannot scroll down -> UP) and relative
                //    element order (target index vs visible elements average index).
                // 2. Historical Motion Fallback:
                //    If element order is indeterminate, falls back to lastKnownTargetBounds screen position.
                val resolvedDir = elementFinder.determineScrollDirection(targetStep, activePkg, activeScreen)
                if (resolvedDir != null) {
                    targetScrolledAbove = (resolvedDir == ElementMarkerOverlay.ScrollDirection.UP)
                    FlightRecorder.record("SCROLL_DIRECTION", "Resolved scroll direction for '$targetStep': $resolvedDir (targetScrolledAbove=$targetScrolledAbove)", "Screen: '$activeScreen'")
                } else {
                    val screenH = try {
                        Resources.getSystem().displayMetrics.heightPixels
                    } catch (_: Exception) { 2400 }

                    val lastBounds = lastKnownTargetBounds
                    if (lastBounds != null) {
                        if (lastBounds.centerY() < screenH * 0.45f) {
                            targetScrolledAbove = true
                        } else if (lastBounds.centerY() > screenH * 0.55f) {
                            targetScrolledAbove = false
                        }
                    }
                }

                // ── Safe auto-advance for generic root steps (e.g. "Settings") ──
                var skipAdvanceToNext = false
                val nStep = nextStep
                if (currentIndex == 0 && nStep != null) {
                    val genericRoots = setOf("home", "main", "start", "landing", "settings", "options", "menu")
                    if (genericRoots.contains(targetStep.trim().lowercase())) {
                        val nextResult = elementFinder.findElement(nStep, activePkg, activeScreen, prevStep ?: "")
                        if (nextResult.found) {
                            skipAdvanceToNext = true
                        }
                    }
                }

                val isLockedOnRoute = currentTrackerState is TrackerState.OnRoute && lastWrongTapText == null
                val wrongTap = lastWrongTapText?.trim()
                val wrongTapOccurred = !wrongTap.isNullOrEmpty() && !wrongTap.equals("null", ignoreCase = true) && !wrongTap.equals("nil", ignoreCase = true)
                val isOffTrack = !isLockedOnRoute && (isUnrecognizedScreen || isCurrentScreenUnrecognized || wrongTapOccurred)

                // [PERMANENT FIX] Do not remove this! Check scrollability on background IO thread
                // to completely eliminate disk I/O and JSON parsing on Android's Main UI Thread.
                val hasScrollable = elementFinder.hasScrollableContainer()

                postToMain {
                    if (!isNavigating || targetStep != currentTarget) return@postToMain

                    val triggerReroute = { onRerouteRequested?.invoke(targetPackage, initialTask) ?: Unit }

                    // [PERMANENT FIX] Do not remove this! Target in-context guard against false scroll directives.
                    // Never instruct the user to scroll to find targetStep if they are on an unrecognized screen,
                    // tapped a wrong option, or are currently off track.
                    if (isOffTrack) {
                        marker?.hideMarker()
                        val currentDiv = currentTrackerState as? TrackerState.Diverged
                        val originNodeTitle = currentDiv?.originNode?.screenTitle ?: targetAppLabel
                        val targetName = currentDiv?.originNode?.targetElement ?: targetStep
                        val steps = currentDiv?.backStepsRequired ?: 1
                        val (backAction, ctx) = formatRecoveryGuidance(originNodeTitle, targetName, steps)
                        val action = if (wrongTapOccurred) "You tapped '$wrongTap'" else backAction
                        val contextTxt = if (wrongTapOccurred) backAction else ctx

                        hud.updateHud(
                            stepHeader        = "WRONG PATH",
                            actionInstruction = action,
                            breadcrumb        = null, // [PERMANENT FIX] Hide future breadcrumb in wrong-path state to eliminate visual clutter
                            contextText       = contextTxt,
                            subTag            = "unrecognized-screen-back",
                            onHelpRequested   = triggerReroute,
                            hudState          = NavigationHudOverlay.HudState.OFF_TRACK
                        )
                        // [PERMANENT FIX] Do not remove this! Highlight system navbar or toolbar Back button on off-track screen
                        highlightBackAction()
                        return@postToMain
                    }

                    if (skipAdvanceToNext) {
                        marker?.hideMarker()
                        Log.i(TAG, "Search pipeline: generic root '$targetStep' not found, but next step '${nStep}' IS on screen. Auto-advancing.")
                        lastCorrectStep = targetStep
                        advanceStep(currentIndex)
                        return@postToMain
                    }

                    if (hasScrollable) {
                        val scrollDir = if (targetScrolledAbove) {
                            ElementMarkerOverlay.ScrollDirection.UP
                        } else {
                            ElementMarkerOverlay.ScrollDirection.DOWN
                        }
                        marker?.showScrollArrow(scrollDir)

                        val scrollText = if (targetScrolledAbove) {
                            "Scroll up to find '$targetStep'"
                        } else {
                            "Scroll down to find '$targetStep'"
                        }
                        val contextTxt = if (targetScrolledAbove) {
                            "Passed it — scroll back up"
                        } else {
                            "Not visible yet"
                        }
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = scrollText,
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = contextTxt,
                            subTag            = "scroll-directive",
                            onHelpRequested   = triggerReroute,
                            hudState          = NavigationHudOverlay.HudState.SEARCHING
                        )
                    } else if (nStep != null) {
                        marker?.hideMarker()
                        // Combined 2-step hint: find the current element, then immediately tap next
                        val combinedHint = buildCombinedHint(targetStep, nStep)
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = combinedHint,
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Not found — try combined action",
                            subTag            = "combined-hint",
                            onHelpRequested   = triggerReroute,
                            hudState          = NavigationHudOverlay.HudState.SEARCHING
                        )
                    } else {
                        marker?.hideMarker()
                        // [PERMANENT FIX] Do not remove this! When the element is not found on screen,
                        // display orange NOT FOUND warning on the HUD with the Reroute button so the user can request AI path re-anchoring.
                        hud.updateHud(
                            stepHeader        = formatStepHeader("STEP ${currentIndex + 1} OF ${pathSteps.size}"),
                            actionInstruction = "Looking for '$targetStep'",
                            breadcrumb        = buildBreadcrumb(),
                            contextText       = "Not found on screen",
                            subTag            = "not-found-reroute",
                            onHelpRequested   = triggerReroute,
                            hudState          = NavigationHudOverlay.HudState.SEARCHING
                        )
                    }
                }
            }
        }

        fun onScreenMotion() {
            if (!isNavigating || currentTarget.isEmpty()) return
            // React immediately to screen motion with 0ms delay to track the element smoothly
            assignMission(
                targetStep = currentTarget,
                activePkg = lastKnownActivePackage,
                activeScreen = activeScreenName,
                isUnrecognizedScreen = isCurrentScreenUnrecognized,
                delayMs = 0L
            )
        }

        fun shutdown(isPermanentStop: Boolean = false) {
            scoutJob?.cancel()
            scoutJob = null
            currentTarget = ""
            currentTargetBounds = null
            if (isPermanentStop) {
                lastKnownTargetBounds = null
                lastKnownTargetStep = ""
                targetScrolledAbove = false
            }
            postToMain {
                marker?.hideMarker()
            }
        }
    }

    // ── Element search pipeline ────────────────────────────────────────────────

    private fun triggerSearchPipeline(
        targetStep: String,
        activePkg: String,
        activeScreen: String,
        isUnrecognizedScreen: Boolean = false,
        delayMs: Long = 400L
    ) {
        MarkerScout.assignMission(targetStep, activePkg, activeScreen, isUnrecognizedScreen, delayMs)
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
            if (wasRerouted) {
                // [PERMANENT FIX] Do not upload partial paths generated via Reroute!
                Log.i("[NetworkClient]", "Path was generated via Reroute (partial path). Skipping Firestore upload.")
            } else {
                // [PERMANENT FIX] Do not remove this! Use canonical human-readable app label
                // and pass targetPackage as alias, ensuring Tier 2 queries match on both.
                val canonicalApp = targetAppLabel.ifBlank { targetPackage }
                Log.i("[NetworkClient]", "Uploading successful path to Firestore for '$canonicalApp' (pkg: $targetPackage): $pathStr")
                CloudPathDatabase.addEntry(canonicalApp, initialTask, pathStr, targetPackage = targetPackage)
            }

            postToMain {
                hud.updateHud(
                    stepHeader        = "SUCCESS ✓",
                    actionInstruction = "Path saved!",
                    breadcrumb        = buildBreadcrumb(),
                    subTag            = "saved",
                    hudState          = NavigationHudOverlay.HudState.SUCCESS
                )
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
        val pmMatches = try {
            val resolvedLabel = MainFilter.resolveAppLabel(context, activePackage).lowercase()
            resolvedLabel == label ||
                resolvedLabel.contains(label) ||
                label.contains(resolvedLabel)
        } catch (e: Exception) {
            false
        }
        if (pmMatches) return true

        // Standard Android Settings satellite packages (Security & Privacy, Permission Controller)
        val standardSettingsSatellites = setOf("com.google.android.permissioncontroller", "com.android.permissioncontroller")
        if (target.contains("settings") && standardSettingsSatellites.contains(pkg)) return true

        // [PERMANENT FIX] Do not remove this! Layer 4: Connected cluster check via NavGraphDatabase.
        // OEM ROMs (ColorOS, OneUI, MIUI) delegate Settings sub-pages to satellite packages
        // (e.g. com.oplus.sos, com.oplus.wallpapers, com.oplus.wirelesssettings, com.oplus.battery).
        // Checks whether activePackage belongs to targetPackage's connected graph cluster.
        if (connectedTargetPackages.contains(pkg)) return true

        return try {
            if (::context.isInitialized && target.isNotBlank()) {
                val db = NavGraphDatabase.getInstance(context)
                val connected = db.getConnectedScreens(targetPackage).map { it.packageName.lowercase() }.toSet()
                if (connected.isNotEmpty()) {
                    connectedTargetPackages = connected + target
                    connectedTargetPackages.contains(pkg)
                } else false
            } else false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns true when [stepNorm] (a path step in lowercase) refers to the
     * root/launch step of the target app — e.g. "clock", "whatsapp", "gmail".
     */
    private fun appRootFuzzyMatch(stepNorm: String, stepIndex: Int): Boolean {
        val label  = targetAppLabel.lowercase()
        val pkgEnd = targetPackage.substringAfterLast('.').lowercase()
        
        return stepNorm == label ||
               stepNorm == pkgEnd ||
               stepNorm == targetPackage ||
               label.contains(stepNorm) ||
               stepNorm.contains(label) ||
               pkgEnd.contains(stepNorm) ||
               stepNorm.contains(pkgEnd) ||
               (stepIndex == 0 && stepNorm == "home")
    }

    enum class MatchContext(
        val similarityThreshold: Double,
        val minLengthRatio: Double,
        val allowPrefixMatch: Boolean
    ) {
        /** Strict (90% similarity, 0.80 min length ratio, no prefix): Prevents accidental jumps when checking future steps. */
        FUTURE_STEP_SKIP(similarityThreshold = 0.90, minLengthRatio = 0.80, allowPrefixMatch = false),

        /** Medium (80% similarity, 0.65 min length ratio, prefix allowed for compound rows): Handles preference row labels (Title + Subtitle). */
        CURRENT_STEP_TAP(similarityThreshold = 0.80, minLengthRatio = 0.65, allowPrefixMatch = true),

        /** High / Screen Title (85% similarity, 0.70 min length ratio, no prefix): Used for comparing screen titles and unwinding diverged stacks. */
        SCREEN_TITLE(similarityThreshold = 0.85, minLengthRatio = 0.70, allowPrefixMatch = false),

        /** Permissive / Low (70% similarity, 0.55 min length ratio, no prefix): Used for flexible queries or loose fallback matching. */
        PERMISSIVE(similarityThreshold = 0.70, minLengthRatio = 0.55, allowPrefixMatch = false)
    }

    /**
     * [PERMANENT FIX] Do not remove this! Calibrated 80% Similarity Matcher with Control Word Blacklist.
     * [PERMANENT FIX] Do not remove this! Smart Multi-Tiered Contextual Fuzzy Threshold Architecture for Taps vs Skips vs Titles.
     * Prevents loose substring containment bugs (e.g. "back" matching "chat backup" or "home" matching "home screen").
     * 1. Exact equality or clean alphanumeric match returns true (100%).
     * 2. Plural normalization (e.g. "chats" vs "chat", "notifications" vs "notification") returns true.
     * 3. Control words ("back", "home", "menu", "navigate up", "close", "cancel") must match exactly in all contexts.
     * 4. Contextual Compound Row Prefix Matching: in CURRENT_STEP_TAP, allows Title + Subtitle compound rows (e.g. "Chats Chat history, backup").
     * 5. Contextual Thresholds: FUTURE_STEP_SKIP enforces 90% threshold + 80% length ratio to block accidental skips;
     *    CURRENT_STEP_TAP enforces 80% threshold + 65% length ratio; SCREEN_TITLE enforces 85% threshold + 70% length ratio.
     */
    internal fun fuzzyMatch(actual: String, expected: String, context: MatchContext = MatchContext.SCREEN_TITLE): Boolean {
        val a = actual.trim().lowercase()
        val e = expected.trim().lowercase()
        if (a == e) return true

        val aClean = a.replace(Regex("[^a-z0-9]"), "")
        val eClean = e.replace(Regex("[^a-z0-9]"), "")
        if (aClean == eClean && aClean.isNotEmpty()) return true

        // Pluralization tolerance (e.g. chats vs chat, apps vs app, notifications vs notification)
        if (aClean.removeSuffix("s") == eClean.removeSuffix("s") && aClean.removeSuffix("s").length >= 3) {
            return true
        }

        // Control word blacklist: single control tokens cannot match compound targets in any context
        val controlWords = setOf("back", "home", "menu", "navigate up", "close", "cancel", "more", "options", "null", "nil")
        if ((controlWords.contains(a) || controlWords.contains(e)) && a != e) {
            return false
        }

        // Contextual compound row prefix matching (Only allowed in CURRENT_STEP_TAP)
        // Handles Android preference rows concatenating Title + Subtitle (e.g. 'Chats Chat history, backup')
        if (context.allowPrefixMatch && eClean.length >= 3) {
            val naturalSeparators = setOf(' ', '\n', '\r', '\t', ':', '-', '—', '·', ',', ';', '(', '/')
            if (a.startsWith(e) && a.length > e.length && naturalSeparators.contains(a[e.length])) {
                return true
            }
            val eSingular = e.removeSuffix("s")
            if (eSingular.length >= 3 && a.startsWith(eSingular) && a.length > eSingular.length && naturalSeparators.contains(a[eSingular.length])) {
                return true
            }
            val ePlural = "${e}s"
            if (a.startsWith(ePlural) && a.length > ePlural.length && naturalSeparators.contains(a[ePlural.length])) {
                return true
            }
        }

        val maxLen = maxOf(aClean.length, eClean.length)
        if (maxLen == 0) return false
        val minLen = minOf(aClean.length, eClean.length)
        if (minLen.toDouble() / maxLen < context.minLengthRatio) {
            return false
        }

        val dist = computeLevenshtein(aClean, eClean)
        val similarity = 1.0 - (dist.toDouble() / maxLen)
        if (similarity >= context.similarityThreshold) return true

        // Token-level Jaccard similarity for multi-word phrases (calibrated to context similarity threshold)
        val aTokens = a.split(" ", "-", "_").filter { it.length >= 3 }.toSet()
        val eTokens = e.split(" ", "-", "_").filter { it.length >= 3 }.toSet()
        if (aTokens.isNotEmpty() && eTokens.isNotEmpty()) {
            val intersection = aTokens.intersect(eTokens).size
            val union = aTokens.union(eTokens).size
            val jaccard = intersection.toDouble() / union
            val jaccardThreshold = if (context == MatchContext.FUTURE_STEP_SKIP) 0.90 else 0.75
            if (jaccard >= jaccardThreshold) return true
        }

        return false
    }

    internal fun computeLevenshtein(s1: String, s2: String): Int {
        val dp = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..s2.length) {
                val temp = dp[j]
                dp[j] = if (s1[i - 1] == s2[j - 1]) prev else 1 + minOf(prev, dp[j], dp[j - 1])
                prev = temp
            }
        }
        return dp[s2.length]
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

    /**
     * Resolves the clean, user-visible screen title using [ScreenTitleResolver].
     * [PERMANENT FIX] Do not remove this! Uses universal ScreenTitleResolver with proper Binder recycling
     * to ensure HUD, database, and telemetry display the exact same clean screen name.
     */
    private fun findPageTitle(
        root: android.view.accessibility.AccessibilityNodeInfo,
        appLabel: String = "",
        pkg: String = ""
    ): String? {
        val label = appLabel.ifBlank { targetAppLabel }
        val packageNameToUse = pkg.ifBlank { lastKnownActivePackage }
        return ScreenTitleResolver.resolveTitle(root, label, packageNameToUse)
    }
}
