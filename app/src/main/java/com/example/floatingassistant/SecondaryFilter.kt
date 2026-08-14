package com.example.floatingassistant

import android.util.Log

/**
 * SecondaryFilter — Phase 4
 *
 * Responsibility:
 *  1. VIEWPORT HASH CHECK  — skip immediately if the visible tree hasn't changed since
 *                            the last processed frame for this package (handles rapid re-fires).
 *  2. CONTENT ITEM EXTRACTION — find the "primary content container" (the node with the
 *                            most direct children) and treat its children as the items to track.
 *  3. SCROLL DEDUPLICATION — per (package, rootName): maintain a [LinkedHashSet] of seen
 *                            node IDs. New items = items not yet in the set.
 *  4. SCROLL-UP DETECTION  — if 0 new items found → user scrolled up (or no real change) → skip.
 *  5. ACCUMULATION         — append new items to the screen's ordered list.
 *
 * Returns a [ProcessResult] so the caller decides what to log / write.
 */
object SecondaryFilter {

    private const val TAG = "SecondaryFilter"

    // ── Accumulated state ─────────────────────────────────────────────────────

    /** All state for one app. Key = rootName (e.g. "Settings", "Bluetooth"). */
    data class ScreenState(
        val rootName: String,
        /** Ordered set of nodeIds already captured — used for O(1) dedup. */
        val seenIds: LinkedHashSet<Long> = LinkedHashSet(),
        /** The accumulated, deduplicated, ordered list of content items. */
        val items: MutableList<UiNode>   = mutableListOf()
    )

    /** Full accumulated state for a single app package. */
    data class AppState(
        /** packageName → (rootName → ScreenState). Using LinkedHashMap preserves nav order. */
        val screens: LinkedHashMap<String, ScreenState> = LinkedHashMap()
    )

    /** Master map: packageName → AppState. */
    val appStates: HashMap<String, AppState> = HashMap()

    // Per-package last viewport hash — used to skip identical frames.
    private val lastViewportHash = HashMap<String, Long>()

    // ── Public result type ────────────────────────────────────────────────────

    sealed class ProcessResult {
        /** Identical content to last frame, or scroll-up with no new items. */
        object Skipped : ProcessResult()

        /** Navigation to a new or existing root. Returns ALL items for that root so far. */
        data class RootChanged(
            val packageName: String,
            val rootName: String,
            val allItems: List<UiNode>
        ) : ProcessResult()

        /** Scroll revealed new items; they have been appended. */
        data class ScrollAppended(
            val packageName: String,
            val rootName: String,
            val newItems: List<UiNode>,
            val totalItems: Int
        ) : ProcessResult()

        /** First time seeing this package+root combo. All current items are "new". */
        data class NewScreen(
            val packageName: String,
            val rootName: String,
            val items: List<UiNode>
        ) : ProcessResult()
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Process one parsed [UiNode] tree through the secondary filter.
     *
     * @param uiTree       Root [UiNode] from the Inbetween Filter.
     * @param packageName  Package that owns the window.
     * @param rootName     Current contextual root (from [ContextRootTracker]).
     * @param isNavigation True when triggered by TYPE_WINDOW_STATE_CHANGED (new screen),
     *                     false when triggered by TYPE_WINDOW_CONTENT_CHANGED (scroll/update).
     */
    fun process(
        uiTree: UiNode,
        packageName: String,
        rootName: String,
        isNavigation: Boolean
    ): ProcessResult {

        // ── 1. Viewport hash check ────────────────────────────────────────────
        val viewportHash = computeViewportHash(uiTree)
        val lastHash     = lastViewportHash[packageName]

        if (!isNavigation && viewportHash == lastHash) {
            Log.v(TAG, "[$packageName/$rootName] Skipped — identical viewport hash")
            return ProcessResult.Skipped
        }
        lastViewportHash[packageName] = viewportHash

        // ── 2. Extract content items ──────────────────────────────────────────
        val container    = findPrimaryContainer(uiTree)
        val currentItems = container.children

        if (currentItems.isEmpty()) {
            Log.v(TAG, "[$packageName/$rootName] Skipped — no content items extracted")
            return ProcessResult.Skipped
        }

        // ── 3. Get or create state for this screen ────────────────────────────
        val appState    = appStates.getOrPut(packageName) { AppState() }
        val screenState = appState.screens[rootName]

        // ── 4. Navigation: new or revisited root ──────────────────────────────
        if (isNavigation) {
            if (screenState == null) {
                // Brand new screen — seed with current items
                val newState = ScreenState(rootName = rootName)
                currentItems.forEach { item ->
                    if (newState.seenIds.add(item.nodeId)) {
                        newState.items += item
                    }
                }
                appState.screens[rootName] = newState
                Log.i(TAG, "[$packageName/$rootName] NEW screen — ${newState.items.size} items")
                return ProcessResult.NewScreen(packageName, rootName, newState.items.toList())
            } else {
                // Revisited (back navigation) — merge any NEW items but keep existing order
                val newItems = mergeItems(screenState, currentItems)
                Log.i(TAG, "[$packageName/$rootName] ROOT CHANGE (back) — ${screenState.items.size} items total, ${newItems.size} merged")
                return ProcessResult.RootChanged(packageName, rootName, screenState.items.toList())
            }
        }

        // ── 5. Scroll / content change: find new items ────────────────────────
        val state = screenState ?: run {
            // First content event before any navigation event — create screen now
            val newState = ScreenState(rootName = rootName)
            currentItems.forEach { item ->
                if (newState.seenIds.add(item.nodeId)) newState.items += item
            }
            appState.screens[rootName] = newState
            Log.i(TAG, "[$packageName/$rootName] NEW screen (from content event) — ${newState.items.size} items")
            return ProcessResult.NewScreen(packageName, rootName, newState.items.toList())
        }

        val newItems = mergeItems(state, currentItems)

        return if (newItems.isEmpty()) {
            Log.v(TAG, "[$packageName/$rootName] Skipped — scroll-up, 0 new items (${state.items.size} total)")
            ProcessResult.Skipped
        } else {
            Log.i(TAG, "[$packageName/$rootName] APPENDED ${newItems.size} new items (${state.items.size} total)")
            ProcessResult.ScrollAppended(packageName, rootName, newItems, state.items.size)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Adds items from [currentItems] that are NOT already in [state.seenIds].
     * Returns only the truly new items (those appended this call).
     */
    private fun mergeItems(state: ScreenState, currentItems: List<UiNode>): List<UiNode> {
        val newItems = mutableListOf<UiNode>()
        for (item in currentItems) {
            if (state.seenIds.add(item.nodeId)) {
                state.items += item
                newItems    += item
            }
        }
        return newItems
    }

    /**
     * Computes a fast viewport fingerprint: XOR of all nodeIds in the tree.
     * Same tree content → same hash. Different content → (almost certainly) different hash.
     * XOR is associative and commutative so order doesn't matter — we just want a
     * fast "did anything change?" signal, not a perfect hash.
     */
    private fun computeViewportHash(node: UiNode): Long {
        var h = node.nodeId
        for (child in node.children) h = h xor computeViewportHash(child)
        return h
    }

    /**
     * Finds the node with the MOST direct children in the tree (BFS, stops at depth 4).
     * This is typically the RecyclerView / ListView holding the scrollable content.
     *
     * Falls back to the root itself if nothing better is found.
     */
    private fun findPrimaryContainer(root: UiNode): UiNode {
        var best      = root
        var bestCount = root.children.size

        // BFS limited to depth 4 to stay fast
        val queue = ArrayDeque<Pair<UiNode, Int>>()
        queue += root to 0

        while (queue.isNotEmpty()) {
            val (node, depth) = queue.removeFirst()
            if (node.children.size > bestCount) {
                best      = node
                bestCount = node.children.size
            }
            if (depth < 4) node.children.forEach { queue += it to depth + 1 }
        }
        return best
    }

    // ── Pruning API ───────────────────────────────────────────────────────────

    /**
     * Clear the raw [UiNode] items list for one screen after its data has been
     * promoted to Tier 2 and Tier 3.
     *
     * [ScreenState.seenIds] is intentionally KEPT so scroll deduplication
     * continues to work correctly — new scroll items are still identified as
     * "not yet seen" when they appear below the viewport.
     *
     * After pruning, [ScreenState.items] is empty, so the next [JsonTreeWriter]
     * call for this package will produce a much smaller Tier 1 file.
     */
    fun pruneScreenItems(packageName: String, rootName: String) {
        val screen = appStates[packageName]?.screens?.get(rootName) ?: return
        val count  = screen.items.size
        screen.items.clear()
        if (count > 0) Log.d(TAG, "Pruned [$packageName/$rootName] — cleared $count raw UiNode items")
    }

    /**
     * Remove ALL state for [packageName] from memory.
     * Called when the user switches to a different app — the old app's raw data
     * is no longer needed in Tier 1 (it has already been promoted to Tier 3).
     */
    fun prunePackage(packageName: String) {
        val screens = appStates[packageName]?.screens?.size ?: return
        appStates.remove(packageName)
        lastViewportHash.remove(packageName)
        Log.i(TAG, "Pruned package [$packageName] — $screens screen(s) evicted from Tier 1")
    }
}
