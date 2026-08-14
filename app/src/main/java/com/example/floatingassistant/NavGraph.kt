package com.example.floatingassistant

import android.util.Log

/**
 * NavGraph — Phase 6 / Tier 3
 *
 * In-memory representation of the complete, persistent navigation graph.
 * Accumulated across all user sessions; serialized by [NavGraphWriter].
 *
 * Graph semantics:
 *  - NODES    : unique UI elements (id → name), deduplicated globally across all apps/screens.
 *               The same logical element (e.g. "Wi-Fi") is stored once and referenced by id.
 *  - SCREENS  : per-app named sets of node IDs, ordered by discovery time.
 *  - EDGES    : directed navigation links (Screen A → Screen B), with a traversal count.
 *               Edges are deduplicated by (fromScreen, toScreen) pair.
 *
 * Thread-safety: all mutations happen on the Main thread (service scope).
 * [NavGraphWriter.save] snapshots the data before launching IO, so there is
 * no cross-thread mutation risk.
 */
object NavGraph {

    private const val TAG = "NavGraph"

    // ── Global node registry ──────────────────────────────────────────────────
    // Long (nodeId) → String (display name).
    // LinkedHashMap preserves insertion order for deterministic JSON output.
    val nodes: LinkedHashMap<Long, String> = LinkedHashMap()

    // ── Per-app data ──────────────────────────────────────────────────────────

    /** One screen within an app — holds an ordered, deduplicated set of node IDs. */
    data class Screen(
        val rootName: String,
        val nodeIds: LinkedHashSet<Long> = LinkedHashSet()
    )

    /**
     * One directed navigation edge.
     * [viaNodeId] is the ID of the element that was tapped to trigger the transition
     * (null when click-tracking is not yet available).
     * [count] increments each time the same path is taken.
     */
    data class Edge(
        val fromScreen: String,
        val toScreen: String,
        val viaNodeId: Long?,
        var count: Int = 1
    )

    /** Full graph for one app package. */
    data class AppGraph(
        val packageName: String,
        val screens: LinkedHashMap<String, Screen> = LinkedHashMap(),
        val edges: MutableList<Edge>               = mutableListOf()
    )

    /** Master map: packageName → AppGraph. */
    val apps: HashMap<String, AppGraph> = HashMap()

    // ── Public mutation API ───────────────────────────────────────────────────

    /**
     * Merge [elements] into the graph for [packageName] / [rootName].
     *
     * - Registers each element in the global node table (first name wins on collision).
     * - Adds node IDs to the screen's ordered set (skips duplicates).
     *
     * @return Number of newly added nodes (0 = nothing changed).
     */
    fun mergeScreen(
        packageName: String,
        rootName: String,
        elements: List<CleanPageExtractor.CleanElement>
    ): Int {
        if (elements.isEmpty()) return 0

        val app    = apps.getOrPut(packageName) { AppGraph(packageName) }
        val screen = app.screens.getOrPut(rootName) { Screen(rootName) }
        var added  = 0

        for (el in elements) {
            nodes.putIfAbsent(el.id, el.name)          // global dedup
            if (screen.nodeIds.add(el.id)) added++     // screen-level dedup
        }

        if (added > 0) {
            Log.d(TAG, "[$packageName/$rootName] +$added nodes (total: ${screen.nodeIds.size})")
        }
        return added
    }

    /**
     * Record a navigation transition from [fromScreen] to [toScreen].
     *
     * - Ignores self-loops (same screen).
     * - Increments [Edge.count] if an identical edge already exists.
     * - [viaNodeId] is optional; pass null if the triggering node is unknown.
     */
    fun addEdge(
        packageName: String,
        fromScreen: String,
        toScreen: String,
        viaNodeId: Long? = null
    ) {
        if (fromScreen.isBlank() || toScreen.isBlank()) return
        if (fromScreen == toScreen) return

        val app      = apps.getOrPut(packageName) { AppGraph(packageName) }
        val existing = app.edges.find {
            it.fromScreen == fromScreen &&
            it.toScreen   == toScreen   &&
            it.viaNodeId  == viaNodeId
        }

        if (existing != null) {
            existing.count++
            Log.d(TAG, "Edge [$fromScreen → $toScreen] count=${existing.count}")
        } else {
            app.edges += Edge(fromScreen, toScreen, viaNodeId)
            Log.i(TAG, "New edge: [$fromScreen → $toScreen]")
        }
    }

    // ── Statistics ────────────────────────────────────────────────────────────

    fun totalNodes()   = nodes.size
    fun totalScreens() = apps.values.sumOf { it.screens.size }
    fun totalEdges()   = apps.values.sumOf { it.edges.size }

    fun logSummary() {
        Log.i(TAG, "Graph: ${totalNodes()} nodes | ${totalScreens()} screens | ${totalEdges()} edges")
        for ((pkg, app) in apps) {
            Log.i(TAG, "  $pkg → ${app.screens.keys.joinToString(", ")}")
            for (edge in app.edges) {
                Log.i(TAG, "    [${edge.fromScreen}] →(x${edge.count})→ [${edge.toScreen}]")
            }
        }
    }
}
