package com.example.floatingassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * UiTreeAccessibilityService — Universal OEM-Agnostic Pipeline (Phase 1 + 2 + 3)
 *
 * ── Event routing ─────────────────────────────────────────────────────────────
 *
 *   TYPE_VIEW_CLICKED
 *       • Immediate, no debounce
 *       • Captures the tapped element's label → passed to GraphStateMachine as
 *         the CLICK edge label for the next FORWARD navigation
 *
 *   TYPE_WINDOW_STATE_CHANGED  → NAVIGATION
 *       • Immediate (no debounce)
 *       • Resets Phase 1 accumulation for this package
 *       • Triggers Phase 2 (extract + write clean_page.json)
 *       • Triggers Phase 3 (NAVIGATION event → graph update)
 *
 *   TYPE_VIEW_SCROLLED
 *   TYPE_WINDOW_CONTENT_CHANGED → SCROLL / CONTENT
 *       • Debounced 300 ms
 *       • packageName captured before debounce; rootInActiveWindow fetched fresh after
 *       • Cross-checks active window package to guard against app switches during debounce
 *       • Triggers Phase 2 + 3 ONLY if Phase 1 found new nodes (scroll-up = skip)
 *
 * ── Phase pipeline per event ──────────────────────────────────────────────────
 *
 *   Main thread:
 *     RawDumpWriter.onNavigation / onScroll   [Phase 1 — synchronous tree traversal]
 *     → rawNodes snapshot in memory
 *
 *   IO coroutine (launched from main thread):
 *     CleanPageProcessor.extractSync()        [Phase 2 — CPU extraction]
 *     CleanPageProcessor.writeToFile()        [Phase 2 — async disk write]
 *     GraphStateMachine.onEvent()             [Phase 3 — DB update + nav_graph.json]
 *
 * ── Output files ──────────────────────────────────────────────────────────────
 *   temp_tree.json   — accumulated raw nodes (Phase 1)
 *   clean_page.json  — clean elements (Phase 2)
 *   nav_graph.json   — human-readable graph snapshot (Phase 3)
 *   nav_graph.db     — SQLite graph database (Phase 3, persistent)
 *
 *   Pull all: adb pull /sdcard/Android/data/com.example.floatingassistant/files/
 */
class UiTreeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG         = "UiTreeService"
        private const val DEBOUNCE_MS = 300L

        var instance: UiTreeAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var captureEnabled = false
    private var debounceJob: Job? = null
    private var fastTrackJob: Job? = null

    private lateinit var tempTreeFile:  File
    private lateinit var cleanPageFile: File
    private lateinit var navGraphFile:  File

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = buildServiceInfo()

        // [PERMANENT FIX] Do not remove this! Save all files to internal memory (filesDir)
        // so ElementFinderEngine and other components read from the exact same internal storage folder.
        val targetDir  = filesDir
        tempTreeFile   = File(targetDir, RawDumpWriter.TEMP_FILE_NAME)
        cleanPageFile  = File(targetDir, CleanPageProcessor.CLEAN_FILE_NAME)
        navGraphFile   = File(targetDir, GraphStateMachine.NAV_GRAPH_FILE_NAME)

        instance = this

        // Initialise Phase 3 state machine (opens SQLite DB)
        GraphStateMachine.init(this, navGraphFile)

        // [PERMANENT FIX] Initialize local FlightRecorder (15 MB black box log in filesDir)
        FlightRecorder.init(this)

        // [PERMANENT FIX] Stream continuous heartbeat to Live Dashboard
        DebugTelemetryClient.startHeartbeat()

        // [PERMANENT FIX] Rebind Floating Overlay bubble to TYPE_ACCESSIBILITY_OVERLAY so it stays visible on Settings sub-pages
        FloatingOverlayService.instance?.rebindBubbleToAccessibilityOverlay()

        Log.i(TAG, "Service connected [Phase 1 + 2 + 3]")
        Log.i(TAG, "Pull: adb pull /sdcard/Android/data/$packageName/files/")

        serviceScope.launch {
            ServiceStateManager.isServiceEnabled.collectLatest { enabled ->
                captureEnabled = enabled
                Log.i(TAG, "Capture ${if (enabled) "ON" else "OFF"}")
                if (!enabled) debounceJob?.cancel()
            }
        }

        // ── Fallback Poller for ColorOS/Aggressive OEMs ──────────────────────────
        // Some OEMs eat TYPE_WINDOW_STATE_CHANGED events for their own launchers
        // or other system apps. This ensures NavigationStateMachine always knows
        // the true active package.
        serviceScope.launch {
            while (isActive) {
                delay(500)
                if (captureEnabled && NavigationStateMachine.isNavigating) {
                    var activePkg: String? = null
                    
                    // [PERMANENT FIX] Do not use raw rootInActiveWindow which returns GuidanceApp overlay.
                    // Use AppWindowResolver to find the true underlying application window.
                    val root = AppWindowResolver.getUnderlyingAppRoot(this@UiTreeAccessibilityService, packageName)
                    if (root != null) {
                        activePkg = root.packageName?.toString()
                        root.recycle()
                    }

                    // [PERMANENT FIX] Do not remove this! Detect silent app switch to any TYPE_APPLICATION activity (including MainActivity & Launchers)
                    if (activePkg != null &&
                        !activePkg.startsWith("com.android.systemui") &&
                        activePkg != NavigationStateMachine.lastKnownActivePackage) {

                        Log.w(TAG, "Poller detected silent app switch to: $activePkg")
                        NavigationStateMachine.lastKnownActivePackage = activePkg
                        val launcherPkg = NavigationStateMachine.getDefaultLauncherPackage()
                        val isLauncher = (launcherPkg != null && (activePkg.startsWith(launcherPkg) || activePkg == launcherPkg)) ||
                                         activePkg.contains("launcher", ignoreCase = true) ||
                                         activePkg == "com.android.launcher"
                        val screenTitle = if (isLauncher) "Home" else activePkg.substringAfterLast('.')
                        NavigationStateMachine.onScreenChanged(activePkg, screenTitle, "")
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!captureEnabled) return
        if (event == null)   return

        when (event.eventType) {

            // ── Click: capture label for edge annotation ───────────────────────
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkg   = event.packageName?.toString() ?: return
                // [PERMANENT FIX] Do not simplify this block. We must join all texts 
                // and fallback to event.source to properly capture clicks on containers.
                // Filter out 'null' and 'nil' strings produced by empty collections or unlabelled icons.
                var label = event.contentDescription?.toString()?.trim()
                if (label.isNullOrEmpty() || label.equals("null", ignoreCase = true) || label.equals("nil", ignoreCase = true)) {
                    val validTexts = event.text
                        ?.mapNotNull { it?.toString()?.trim() }
                        ?.filter { it.isNotEmpty() && !it.equals("null", ignoreCase = true) && !it.equals("nil", ignoreCase = true) }
                    label = if (!validTexts.isNullOrEmpty()) validTexts.joinToString(" ") else null
                }
                if (label.isNullOrEmpty() || label.equals("null", ignoreCase = true) || label.equals("nil", ignoreCase = true)) {
                    val source = event.source
                    if (source != null) {
                        try {
                            val srcText = source.text?.toString()?.trim()
                            val srcDesc = source.contentDescription?.toString()?.trim()
                            label = if (!srcText.isNullOrEmpty() && !srcText.equals("null", ignoreCase = true) && !srcText.equals("nil", ignoreCase = true)) srcText
                                    else if (!srcDesc.isNullOrEmpty() && !srcDesc.equals("null", ignoreCase = true) && !srcDesc.equals("nil", ignoreCase = true)) srcDesc
                                    else null

                            if (label.isNullOrEmpty()) {
                                // [PERMANENT FIX] Do not remove this! In compound list items (e.g. COUIPreference rows in Settings),
                                // the container view itself has null text/desc. Extract first meaningful text from child nodes.
                                label = findFirstTextInSubtree(source)
                            }
                        } finally {
                            try { source.recycle() } catch (_: Exception) {}
                        }
                    }
                }
                label = label?.trim() ?: ""
                // [PERMANENT FIX] Do not remove this! Filter literal 'null' and 'nil' strings produced by
                // empty event.text collections to prevent false "You tapped 'null'" alerts on Back button presses.
                if (label.equals("null", ignoreCase = true) || label.equals("nil", ignoreCase = true)) {
                    label = ""
                }
                if (label.isNotEmpty()) {
                    FlightRecorder.record("VIEW_CLICKED", "User clicked view with label '$label'", "pkg='$pkg'")
                    GraphStateMachine.setLastClickedLabel(pkg, label)

                    // Notify the navigation engine so it can advance when the
                    // user taps a menu item / bottom-sheet option that matches the
                    // current path step (overlays never fire WINDOW_STATE_CHANGED).
                    if (NavigationStateMachine.isNavigating) {
                        NavigationStateMachine.onElementTapped(label, pkg)
                    }
                }
            }


            // ── Navigation: immediate ──────────────────────────────────────────
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isRelevantEvent(event)) return
                
                val pkg = event.packageName?.toString() ?: ""
                if (!pkg.startsWith("com.android.systemui") && pkg != this.packageName) {
                    debounceJob?.cancel()
                }
                
                handleNavigation(event)
            }

            // ── Scroll / content: debounced 300 ms ────────────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isRelevantEvent(event)) return
                
                val expectedPkg = event.packageName?.toString() ?: return
                if (expectedPkg.startsWith("com.android.systemui") || expectedPkg == this.packageName) {
                    return // Ignore noise, preserve existing debounce
                }
                
                // Fast tracking: instantly update the marker with minimal debounce (30ms)
                // so it follows the user's scroll smoothly without waiting for the heavy disk dump.
                if (NavigationStateMachine.isNavigating) {
                    fastTrackJob?.cancel()
                    fastTrackJob = serviceScope.launch {
                        delay(30L)
                        NavigationStateMachine.onContentScrolled()
                    }
                }
                
                debounceJob?.cancel()
                val isScroll = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                debounceJob = serviceScope.launch {
                    delay(DEBOUNCE_MS)
                    handleScroll(expectedPkg, if (isScroll) "SCROLL" else "CONTENT_CHANGED")
                }
            }
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

    override fun onDestroy() {
        DebugTelemetryClient.stopHeartbeat()
        debounceJob?.cancel()
        fastTrackJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ── NAVIGATION handler ────────────────────────────────────────────────────

    private fun handleNavigation(event: AccessibilityEvent) {
        // ── Use rootInActiveWindow as the authoritative package source ──
        // event.packageName is unreliable — it captures Toast messages, SystemUI
        // swipes, input-method windows, and invisible overlays, all of which would
        // falsely trigger OFF-TRACK in the navigation state machine.
        val trustedRoot   = rootInActiveWindow
        val activePackage = trustedRoot?.packageName?.toString()
            ?: event.packageName?.toString()
        if (activePackage == null) {
            trustedRoot?.recycle()
            return   // can't determine package — skip entirely
        }

        // [PERMANENT FIX] Do not remove this! Handle app switch to GuidanceApp MainActivity during navigation
        if (activePackage == this.packageName) {
            trustedRoot?.recycle()
            if (NavigationStateMachine.isNavigating) {
                NavigationStateMachine.lastKnownActivePackage = activePackage
                NavigationStateMachine.onScreenChanged(activePackage, "Floating Assistant", "")
            }
            return
        }

        // Ignore events from SystemUI (swipe gestures, notification shade, nav bar)
        if (activePackage.startsWith("com.android.systemui")) {
            trustedRoot?.recycle()
            Log.v(TAG, "handleNavigation: skipping noise from $activePackage")
            return
        }

        // Update the state machine's last-known package (used for fast-forward)
        NavigationStateMachine.lastKnownActivePackage = activePackage

        // For Phase 1 we still need a rootNode — prefer the live rootInActiveWindow
        // (already fetched above) over event.source since event.source reflects the
        // *event origin* node, not necessarily the full window root.
        val rootNode = trustedRoot ?: event.source
        if (rootNode == null) {
            // Even if we can't extract elements, tell the state machine the user switched apps.
            // This is critical for detecting when the user returns to the Home screen or switches
            // to a different app (where rootInActiveWindow may briefly be null).
            if (NavigationStateMachine.isNavigating) {
                NavigationStateMachine.onScreenChanged(activePackage, activePackage.substringAfterLast('.'), "")
            }
            return
        }

        val launcherPkg = NavigationStateMachine.getDefaultLauncherPackage()

        val filterResult = MainFilter.apply(
            context        = applicationContext,
            packageName    = activePackage,
            rootNode       = rootNode,
            ownPackage     = this.packageName,
            launcherPackage = launcherPkg,
            eventClassName = event.className?.toString()
        )
        if (filterResult !is MainFilter.FilterResult.Passed) {
            // Even if we drop semantic extraction (e.g. for launchers or SystemUI),
            // the state machine MUST know the user switched apps so it doesn't get stuck.
            if (NavigationStateMachine.isNavigating) {
                val isLauncher = (launcherPkg != null && (activePackage.startsWith(launcherPkg) || activePackage == launcherPkg)) ||
                                 activePackage.contains("launcher", ignoreCase = true) ||
                                 activePackage == "com.android.launcher"
                val screenTitle = if (isLauncher) "Home" else activePackage.substringAfterLast('.')
                NavigationStateMachine.onScreenChanged(activePackage, screenTitle, "")
            }
            // [PERMANENT FIX] Do not remove this! Recycle rootNode when navigation filter fails to prevent native Binder leak
            try { rootNode.recycle() } catch (_: Exception) {}
            return
        }
        val passed = filterResult

        // semanticRootName is now always human-readable: "Clock-Alarm", "Gmail-Inbox", "Home"
        val rootClass = passed.semanticRootName

        // Phase 1 — synchronous tree traversal (must complete before recycle)
        try {
            RawDumpWriter.onNavigation(
                scope       = serviceScope,
                outputFile  = tempTreeFile,
                rootNode    = passed.rootNode,
                packageName = passed.packageName,
                rootName    = rootClass
            )
        } finally {
            passed.rootNode.recycle()
        }

        // Phase 2 + 3 — on IO thread
        triggerPipeline(passed.packageName, rootClass, "NAVIGATION")

        // ── Track 4 / Stage 2+: Notify alignment tracker ────────────────────
        // Pass both the package name and the human-readable app label so the state
        // machine can do PM-level matching without re-querying PM itself.
        if (NavigationStateMachine.isNavigating) {
            NavigationStateMachine.onScreenChanged(passed.packageName, rootClass, passed.appLabel)
        }
    }

    // ── SCROLL / CONTENT handler ──────────────────────────────────────────────

    private fun handleScroll(expectedPackage: String, eventType: String) {
        // Ignore events from SystemUI or our own overlay
        if (expectedPackage.startsWith("com.android.systemui") || expectedPackage == this.packageName) {
            return
        }

        val rootNode = rootInActiveWindow
        if (rootNode == null) {
            // Even if we can't extract elements, tell the state machine the user switched apps
            if (NavigationStateMachine.isNavigating) {
                NavigationStateMachine.onScreenChanged(expectedPackage, expectedPackage.substringAfterLast('.'), "")
            }
            return
        }

        // [PERMANENT FIX] Do not remove this! Handle cross-package transitions occurring during debounce.
        // When launching sub-activities/APEX modules (e.g. Settings -> com.google.android.permissioncontroller),
        // TYPE_WINDOW_STATE_CHANGED may be eaten by OEMs. Instead of silently dropping the event, dispatch
        // onScreenChanged immediately so the state machine updates out of stale/frozen guidance.
        val actualPackage = rootNode.packageName?.toString()
        if (actualPackage == null) {
            rootNode.recycle()
            return
        }
        if (actualPackage != expectedPackage) {
            Log.i(TAG, "[$eventType] Cross-package transition during debounce: expected=$expectedPackage actual=$actualPackage")
            if (NavigationStateMachine.isNavigating && !actualPackage.startsWith("com.android.systemui") && actualPackage != this.packageName) {
                NavigationStateMachine.lastKnownActivePackage = actualPackage
                val launcherPkg = NavigationStateMachine.getDefaultLauncherPackage()
                val isLauncher = (launcherPkg != null && (actualPackage.startsWith(launcherPkg) || actualPackage == launcherPkg)) ||
                                 actualPackage.contains("launcher", ignoreCase = true) ||
                                 actualPackage == "com.android.launcher"
                val appLabel = MainFilter.resolveAppLabel(applicationContext, actualPackage)
                val screenTitle = if (isLauncher) "Home" else ScreenTitleResolver.resolveTitle(rootNode, appLabel, actualPackage)
                NavigationStateMachine.onScreenChanged(actualPackage, screenTitle, appLabel)
            }
            rootNode.recycle()
            return
        }

        val filterResult = MainFilter.apply(
            context         = applicationContext,
            packageName     = actualPackage,
            rootNode        = rootNode,
            ownPackage      = this.packageName,
            launcherPackage = NavigationStateMachine.getDefaultLauncherPackage(),
            eventClassName  = null
        )
        if (filterResult !is MainFilter.FilterResult.Passed) {
            if (NavigationStateMachine.isNavigating) {
                NavigationStateMachine.onScreenChanged(expectedPackage, expectedPackage.substringAfterLast('.'), "")
            }
            // [PERMANENT FIX] Do not remove this! Recycle rootNode when scroll filter fails to prevent native Binder leak
            try { rootNode.recycle() } catch (_: Exception) {}
            return
        }
        val passed = filterResult

        var hadNewNodes = false
        try {
            hadNewNodes = RawDumpWriter.onScroll(
                scope       = serviceScope,
                outputFile  = tempTreeFile,
                rootNode    = passed.rootNode,
                packageName = passed.packageName,
                rootName    = passed.semanticRootName
            )
        } finally {
            passed.rootNode.recycle()
        }

        // Only trigger Phase 2 + 3 if Phase 1 found genuinely new nodes
        // [PERMANENT FIX] Do not remove this! Pass semanticRootName (resolved by ScreenTitleResolver) to pipeline.
        // Prevents raw class names like android.widget.FrameLayout from leaking into GraphStateMachine on scroll/content events.
        if (hadNewNodes) {
            val snapshot = RawDumpWriter.getSnapshot(passed.packageName) ?: return
            triggerPipeline(passed.packageName, passed.semanticRootName, eventType)
        }

        // Always tell the state machine that content shifted, so it can
        // reposition the visual marker bounds if the target element moved.
        if (NavigationStateMachine.isNavigating) {
            NavigationStateMachine.onContentScrolled()
        }
    }

    // ── Phase 2 + 3 pipeline ──────────────────────────────────────────────────

    /**
     * Runs Phase 2 (clean extraction) and Phase 3 (graph update) on [Dispatchers.IO].
     * The clean elements are extracted once and passed to both Phase 2 (file write)
     * and Phase 3 (DB update + nav_graph.json) without duplication.
     */
    private fun triggerPipeline(packageName: String, rootClass: String, eventType: String) {
        val snapshot = RawDumpWriter.getSnapshot(packageName) ?: return

        serviceScope.launch(Dispatchers.IO) {
            try {
                // Phase 2 — extract (CPU) + write clean_page.json
                val cleanElements = CleanPageProcessor.extractSync(snapshot.nodes, packageName)
                CleanPageProcessor.writeToFile(
                    scope       = serviceScope,
                    outputFile  = cleanPageFile,
                    elements    = cleanElements,
                    packageName = packageName,
                    rootName    = snapshot.rootName
                )

                // Phase 3 — update navigation graph + write nav_graph.json
                GraphStateMachine.onEvent(
                    cleanElements = cleanElements,
                    packageName   = packageName,
                    rootClass     = rootClass,
                    eventType     = eventType
                )
            } catch (e: Exception) {
                Log.e(TAG, "Pipeline failed [$eventType] $packageName: ${e.message}", e)
            }
        }
    }

    // ── Service configuration ──────────────────────────────────────────────────

    private fun buildServiceInfo(): AccessibilityServiceInfo =
        AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED    or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED             or
                    AccessibilityEvent.TYPE_VIEW_CLICKED
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS              or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS               or
                    // Critical for OEM devices (Vivo OriginUI, Samsung OneUI, MIUI, OPPO):
                    // many OEMs mark their inner TextViews/ImageViews as
                    // importantForAccessibility=false to improve performance.
                    // Without this flag those child views — including the text labels
                    // for Bluetooth, Mobile Network, etc. in Settings — are completely
                    // invisible to getChild() traversal.
                    AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

    private fun isRelevantEvent(event: AccessibilityEvent): Boolean =
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
            AccessibilityEvent.TYPE_VIEW_SCROLLED        -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val sub = event.contentChangeTypes
                sub and (AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0
            }
            else -> false   // TYPE_VIEW_CLICKED is handled separately without this check
        }
        
    // [PERMANENT FIX] Do not remove this! Traverses child nodes of compound container rows
    // to extract the clicked label with strict Binder recycling of all allocated children.
    private fun findFirstTextInSubtree(root: AccessibilityNodeInfo, maxDepth: Int = 3): String? {
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        val allocated = mutableListOf<AccessibilityNodeInfo>()
        try {
            while (queue.isNotEmpty()) {
                val (node, depth) = queue.removeFirst()
                if (node !== root) {
                    val text = node.text?.toString()?.trim()
                    if (!text.isNullOrEmpty() && !text.equals("null", ignoreCase = true) && !text.equals("nil", ignoreCase = true)) return text
                    val desc = node.contentDescription?.toString()?.trim()
                    if (!desc.isNullOrEmpty() && !desc.equals("null", ignoreCase = true) && !desc.equals("nil", ignoreCase = true)) return desc
                }
                if (depth < maxDepth) {
                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i) ?: continue
                        allocated.add(child)
                        queue.add(child to depth + 1)
                    }
                }
            }
        } finally {
            allocated.forEach { try { it.recycle() } catch (_: Exception) {} }
        }
        return null
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }
}
