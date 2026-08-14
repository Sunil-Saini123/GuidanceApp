package com.example.floatingassistant

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File

/**
 * UiTreeAccessibilityService — Phase 6 / Phase 11
 *
 * Full 3-tier pipeline with aggressive Tier 1 cleanup and (Phase 11)
 * static-vs-dynamic content classification before Tier 2 / Tier 3 promotion:
 *
 *  ┌──────────────────────────────────────────────────────────────────────────┐
 *  │  Tier 1 Pruning Strategy:                                                │
 *  │   • App switch   → evict old package entirely from SecondaryFilter       │
 *  │                    → rewrite Tier 1 (now only has current app)           │
 *  │   • After promo  → clear raw UiNode items for the just-promoted screen   │
 *  │                    (seenIds kept for scroll dedup)                       │
 *  │                    → rewrite Tier 1 (screen entry is now near-empty)     │
 *  │   • Back-nav     → restore Tier 2 from NavGraph (not from pruned items)  │
 *  ├──────────────────────────────────────────────────────────────────────────┤
 *  │  Phase 11 — Static/Dynamic gate (before Tier 2/3 write):                │
 *  │   SecondaryFilter items → StaticDynamicFilter.classify()                 │
 *  │       → CleanPageExtractor.extract() → Tier 2 / NavGraph                │
 *  └──────────────────────────────────────────────────────────────────────────┘
 *
 * Expected Tier 1 size after pruning: <10 KB at any point in time.
 */
class UiTreeAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG         = "UiTreeService"
        private const val DEBOUNCE_MS = 150L
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var captureEnabled = false
    private var debounceJob: Job? = null

    private val rootTracker = ContextRootTracker()

    // ── Output files (all 3 tiers) ────────────────────────────────────────────
    private lateinit var jsonOutputFile: File   // Tier 1
    private lateinit var cleanPageFile: File    // Tier 2
    private lateinit var graphFile: File        // Tier 3

    // ── Graph loading guard ───────────────────────────────────────────────────
    private var graphLoaded = false

    // ── Edge tracking ─────────────────────────────────────────────────────────
    private var previousRoot: Pair<String, String>? = null

    // ── Tier 1 pruning state ──────────────────────────────────────────────────
    // The package we are actively capturing. When this changes, the old
    // package's raw data is evicted from SecondaryFilter and Tier 1 is rewritten.
    private var activePackage: String? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo    = buildServiceInfo()
        val extDir     = getExternalFilesDir(null) ?: cacheDir
        jsonOutputFile = File(extDir, JsonTreeWriter.FILE_NAME)
        cleanPageFile  = File(extDir, CleanPageWriter.FILE_NAME)
        graphFile      = File(extDir, NavGraphWriter.FILE_NAME)

        Log.i(TAG, "Service connected | " +
                "T1=${jsonOutputFile.name}  T2=${cleanPageFile.name}  T3=${graphFile.name}")

        serviceScope.launch(Dispatchers.IO) {
            NavGraphWriter.load(graphFile)
            graphLoaded = true
            NavGraph.logSummary()
        }

        serviceScope.launch {
            ServiceStateManager.isServiceEnabled.collectLatest { enabled ->
                captureEnabled = enabled
                Log.i(TAG, "Capture ${if (enabled) "ON" else "OFF"}")
                if (!enabled) debounceJob?.cancel()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!captureEnabled) return
        if (event == null)   return
        if (!isRelevantEvent(event)) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                debounceJob?.cancel()
                processFrame(event, isNavigation = true)
            }
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                debounceJob?.cancel()
                debounceJob = serviceScope.launch {
                    delay(DEBOUNCE_MS)
                    processFrame(event, isNavigation = false)
                }
            }
        }
    }

    override fun onInterrupt() { Log.w(TAG, "Service interrupted") }

    override fun onDestroy() {
        debounceJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
    }

    // ── Core pipeline ─────────────────────────────────────────────────────────

    private fun processFrame(event: AccessibilityEvent, isNavigation: Boolean) {
        val packageName = event.packageName?.toString() ?: return
        val rootNode    = event.source ?: rootInActiveWindow

        // ── Main Filter ───────────────────────────────────────────────────────
        val filterResult = MainFilter.apply(packageName, rootNode, this.packageName)
        when (filterResult) {
            is MainFilter.FilterResult.CannotAccess -> return
            is MainFilter.FilterResult.Dropped      -> return
            is MainFilter.FilterResult.Passed       -> Unit
        }
        filterResult as MainFilter.FilterResult.Passed

        // ── Tier 1 pruning: evict old app on package switch ───────────────────
        val pkg = filterResult.packageName
        if (activePackage != null && activePackage != pkg) {
            Log.i(TAG, "[Prune] App switch: $activePackage → $pkg")
            SecondaryFilter.prunePackage(activePackage!!)
            triggerTier1Write()     // Tier 1 now only has the incoming app's data (initially empty)
        }
        activePackage = pkg

        // ── Inbetween Filter (parse) ──────────────────────────────────────────
        val uiTree = UiTreeParser.parse(filterResult.rootNode, filterResult.packageName)
        filterResult.rootNode.recycle()

        // ── Context Root + edge recording ─────────────────────────────────────
        val rootName: String = if (isNavigation) {
            val className = event.className?.toString() ?: ""
            val prevRoot  = rootTracker.currentRoot(pkg)
            val navResult = rootTracker.navigate(pkg, className)
            val newRoot   = navResult.rootName
            if (!navResult.isBack && prevRoot != null && prevRoot != newRoot) {
                NavGraph.addEdge(pkg, prevRoot, newRoot)
            }
            previousRoot = pkg to newRoot
            newRoot
        } else {
            rootTracker.currentRoot(pkg) ?: rootTracker.navigate(pkg, pkg).rootName
        }

        // ── Secondary Filter ──────────────────────────────────────────────────
        val result = SecondaryFilter.process(
            uiTree       = uiTree,
            packageName  = pkg,
            rootName     = rootName,
            isNavigation = isNavigation
        )

        // ── Handle result ─────────────────────────────────────────────────────
        when (result) {
            is SecondaryFilter.ProcessResult.Skipped -> return

            is SecondaryFilter.ProcessResult.NewScreen -> {
                Log.i(TAG, "[P4] NEW  $pkg/$rootName — ${result.items.size} items")
                // Promote to all tiers
                triggerTier1Write()
                processCleanAndGraph(pkg, rootName, result.items, appendClean = false)
                // Tier 1 pruning: raw UiNode data no longer needed for this screen
                SecondaryFilter.pruneScreenItems(pkg, rootName)
                triggerTier1Write()     // rewrite with the now-empty items list
            }

            is SecondaryFilter.ProcessResult.RootChanged -> {
                Log.i(TAG, "[P4] BACK $pkg/$rootName — ${result.allItems.size} items")
                triggerTier1Write()
                // Items may have been pruned; restore Tier 2 from NavGraph instead
                restoreTier2FromGraph(pkg, rootName)
                // Ensure the back-navigation edge is persisted in Tier 3
                if (graphLoaded) triggerTier3Save()
            }

            is SecondaryFilter.ProcessResult.ScrollAppended -> {
                Log.i(TAG, "[P4] SCROLL $pkg/$rootName +${result.newItems.size} new (${result.totalItems} total)")
                // Promote only the NEW items from this scroll
                triggerTier1Write()
                processCleanAndGraph(pkg, rootName, result.newItems, appendClean = true)
                // Prune promoted items from Tier 1 immediately
                SecondaryFilter.pruneScreenItems(pkg, rootName)
                triggerTier1Write()     // rewrite — much smaller now
            }
        }
    }

    // ── Tier write helpers ────────────────────────────────────────────────────

    /** Tier 1: snapshot SecondaryFilter.appStates and write to disk (async). */
    private fun triggerTier1Write() {
        JsonTreeWriter.write(serviceScope, jsonOutputFile, SecondaryFilter.appStates)
    }

    /**
     * Extract clean elements from [items], write to Tier 2, and merge into Tier 3.
     * Call with [appendClean]=false on navigation (fresh Tier 2), true on scroll (merge).
     */
    private fun processCleanAndGraph(
        packageName: String,
        rootName: String,
        items: List<UiNode>,
        appendClean: Boolean
    ) {
        // Phase 11 (v3): Structural & Accessibility-Driven filter.
        // packageName is forwarded for launcher-icon detection (Step 2-E).
        val staticItems = StaticDynamicFilter.classify(items, packageName)
        if (staticItems.isEmpty()) {
            Log.v(TAG, "[P11] All items classified as dynamic for [$rootName] — nothing to promote")
            return
        }

        val cleanElements = CleanPageExtractor.extract(staticItems)
        if (cleanElements.isEmpty()) {
            Log.v(TAG, "[P5/P6] No clean elements for [$rootName]")
            return
        }

        // Tier 2
        CleanPageWriter.write(serviceScope, cleanPageFile, rootName, cleanElements, appendClean)

        // Tier 3
        val added = NavGraph.mergeScreen(packageName, rootName, cleanElements)
        if (added > 0) {
            Log.i(TAG, "[P6] Graph +$added → ${NavGraph.totalNodes()} total nodes")
            triggerTier3Save()
        } else if (!appendClean) {
            triggerTier3Save()  // persist any new edge recorded before this call
        }
    }

    /**
     * Restore the Tier 2 clean file for a screen the user navigated BACK to.
     *
     * The screen's [SecondaryFilter.ScreenState.items] list was pruned after the
     * initial promotion, so we reconstruct Tier 2 from the persistent NavGraph
     * (which always has the full set of clean elements for every visited screen).
     */
    private fun restoreTier2FromGraph(packageName: String, rootName: String) {
        val screen = NavGraph.apps[packageName]?.screens?.get(rootName)
        if (screen == null || screen.nodeIds.isEmpty()) {
            Log.v(TAG, "[P5] No NavGraph data for [$packageName/$rootName] — Tier 2 not restored")
            return
        }
        val elements = screen.nodeIds.mapNotNull { id ->
            val name = NavGraph.nodes[id] ?: return@mapNotNull null
            CleanPageExtractor.CleanElement(id, name)
        }
        if (elements.isNotEmpty()) {
            CleanPageWriter.write(serviceScope, cleanPageFile, rootName, elements, append = false)
            Log.i(TAG, "[P5] Restored Tier 2 from NavGraph: [$rootName] (${elements.size} elements)")
        }
    }

    /** Tier 3: snapshot NavGraph and write to disk (async). Guard: only after graph is loaded. */
    private fun triggerTier3Save() {
        if (!graphLoaded) return
        NavGraphWriter.save(serviceScope, graphFile)
    }

    // ── Service config ────────────────────────────────────────────────────────

    private fun buildServiceInfo(): AccessibilityServiceInfo =
        AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                    AccessibilityEvent.TYPE_VIEW_SCROLLED
            feedbackType        = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags               = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 100
        }

    private fun isRelevantEvent(event: AccessibilityEvent): Boolean =
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> true
            AccessibilityEvent.TYPE_VIEW_SCROLLED          -> true
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                val sub = event.contentChangeTypes
                sub and (AccessibilityEvent.CONTENT_CHANGE_TYPE_SUBTREE or
                        AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT) != 0
            }
            else -> false
        }
}
