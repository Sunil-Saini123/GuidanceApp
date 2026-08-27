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
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var captureEnabled = false
    private var debounceJob: Job? = null

    private lateinit var tempTreeFile:  File
    private lateinit var cleanPageFile: File
    private lateinit var navGraphFile:  File

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = buildServiceInfo()

        val extDir     = getExternalFilesDir(null) ?: cacheDir
        tempTreeFile   = File(extDir, RawDumpWriter.TEMP_FILE_NAME)
        cleanPageFile  = File(extDir, CleanPageProcessor.CLEAN_FILE_NAME)
        navGraphFile   = File(extDir, GraphStateMachine.NAV_GRAPH_FILE_NAME)

        // Initialise Phase 3 state machine (opens SQLite DB)
        GraphStateMachine.init(this, navGraphFile)

        Log.i(TAG, "Service connected [Phase 1 + 2 + 3]")
        Log.i(TAG, "Pull: adb pull /sdcard/Android/data/$packageName/files/")

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

        when (event.eventType) {

            // ── Click: capture label for edge annotation ───────────────────────
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val pkg   = event.packageName?.toString() ?: return
                val label = event.contentDescription?.toString()?.trim()
                    ?: event.text?.firstOrNull()?.toString()?.trim()
                    ?: ""
                if (label.isNotEmpty()) {
                    GraphStateMachine.setLastClickedLabel(pkg, label)
                }
            }

            // ── Navigation: immediate ──────────────────────────────────────────
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                if (!isRelevantEvent(event)) return
                debounceJob?.cancel()
                handleNavigation(event)
            }

            // ── Scroll / content: debounced 300 ms ────────────────────────────
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> {
                if (!isRelevantEvent(event)) return
                debounceJob?.cancel()
                val expectedPkg = event.packageName?.toString() ?: return
                val isScroll    = event.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
                debounceJob = serviceScope.launch {
                    delay(DEBOUNCE_MS)
                    handleScroll(expectedPkg, if (isScroll) "SCROLL" else "CONTENT_CHANGED")
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

    // ── NAVIGATION handler ────────────────────────────────────────────────────

    private fun handleNavigation(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val rootNode    = event.source ?: rootInActiveWindow

        val filterResult = MainFilter.apply(packageName, rootNode, this.packageName)
        if (filterResult !is MainFilter.FilterResult.Passed) return
        val passed = filterResult

        val rootClass = event.className?.toString()
            ?.substringAfterLast('.')
            ?.takeIf { it.isNotEmpty() }
            ?: packageName.substringAfterLast('.')

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
    }

    // ── SCROLL / CONTENT handler ──────────────────────────────────────────────

    private fun handleScroll(expectedPackage: String, eventType: String) {
        val rootNode = rootInActiveWindow ?: return

        // Guard: active window may have changed during debounce
        val actualPackage = rootNode.packageName?.toString()
        if (actualPackage == null || actualPackage != expectedPackage) {
            Log.v(TAG, "[$eventType] Debounce skip: expected=$expectedPackage actual=$actualPackage")
            rootNode.recycle()
            return
        }

        val filterResult = MainFilter.apply(actualPackage, rootNode, this.packageName)
        if (filterResult !is MainFilter.FilterResult.Passed) return
        val passed = filterResult

        var hadNewNodes = false
        try {
            hadNewNodes = RawDumpWriter.onScroll(
                scope       = serviceScope,
                outputFile  = tempTreeFile,
                rootNode    = passed.rootNode,
                packageName = passed.packageName
            )
        } finally {
            passed.rootNode.recycle()
        }

        // Only trigger Phase 2 + 3 if Phase 1 found genuinely new nodes
        if (hadNewNodes) {
            val snapshot = RawDumpWriter.getSnapshot(passed.packageName) ?: return
            triggerPipeline(passed.packageName, snapshot.rootName, eventType)
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
}
