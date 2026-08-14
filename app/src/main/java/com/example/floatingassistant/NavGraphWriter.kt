package com.example.floatingassistant

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * NavGraphWriter — Phase 6 / Tier 3
 *
 * Persistent serialization layer for [NavGraph].
 *
 * Output: /sdcard/Android/data/com.example.floatingassistant/files/ui_nav_graph.json
 *
 * JSON format:
 * ```json
 * {
 *   "version": 1,
 *   "stats": { "nodes": 42, "screens": 8, "edges": 12 },
 *   "nodes": { "<id>": "<name>", ... },
 *   "apps": {
 *     "com.android.settings": {
 *       "screens": {
 *         "SettingsHomepage": {
 *           "root": "SettingsHomepage",
 *           "nodes": [<id>, <id>, ...]
 *         }
 *       },
 *       "edges": [
 *         { "from": "SettingsHomepage", "to": "Bluetooth", "via": null, "count": 3 }
 *       ]
 *     }
 *   }
 * }
 * ```
 *
 * Design decisions:
 *  - Node IDs stored as JSON longs (Android org.json handles 64-bit longs natively).
 *  - The nodes object uses String keys (JSON object keys must be strings).
 *  - [load] runs on the calling thread (call from a background coroutine on startup).
 *  - [save] snapshots the graph on the calling thread, then writes on Dispatchers.IO.
 *    This prevents cross-thread mutation without needing locks.
 */
object NavGraphWriter {

    private const val TAG     = "NavGraphWriter"
    const val FILE_NAME       = "ui_nav_graph.json"
    private const val VERSION = 1

    // ── Load ──────────────────────────────────────────────────────────────────

    /**
     * Deserialize [file] into [NavGraph].
     *
     * Safe to call on any thread. Silently ignores missing, empty, or malformed files.
     * Should be called ONCE on service startup before events begin flowing.
     */
    fun load(file: File) {
        if (!file.exists() || file.length() == 0L) {
            Log.i(TAG, "No existing graph — starting fresh")
            return
        }
        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))

            if (root.optInt("version", 0) != VERSION) {
                Log.w(TAG, "Version mismatch — discarding old graph")
                return
            }

            // ── Global nodes ─────────────────────────────────────────────────
            val nodesObj = root.optJSONObject("nodes")
            nodesObj?.keys()?.forEach { key ->
                val id = key.toLongOrNull() ?: return@forEach
                NavGraph.nodes[id] = nodesObj.getString(key)
            }

            // ── Apps ─────────────────────────────────────────────────────────
            val appsObj = root.optJSONObject("apps") ?: return
            appsObj.keys().forEach { pkgName ->
                val appObj   = appsObj.getJSONObject(pkgName)
                val appGraph = NavGraph.AppGraph(pkgName)

                // Screens
                appObj.optJSONObject("screens")?.keys()?.forEach { rootName ->
                    val screenObj = appObj.getJSONObject("screens").getJSONObject(rootName)
                    val screen    = NavGraph.Screen(rootName)
                    val nodeArr   = screenObj.optJSONArray("nodes")
                    if (nodeArr != null) {
                        for (i in 0 until nodeArr.length()) screen.nodeIds += nodeArr.getLong(i)
                    }
                    appGraph.screens[rootName] = screen
                }

                // Edges
                val edgesArr = appObj.optJSONArray("edges")
                if (edgesArr != null) {
                    for (i in 0 until edgesArr.length()) {
                        val e = edgesArr.getJSONObject(i)
                        appGraph.edges += NavGraph.Edge(
                            fromScreen = e.getString("from"),
                            toScreen   = e.getString("to"),
                            viaNodeId  = if (e.isNull("via")) null else e.getLong("via"),
                            count      = e.optInt("count", 1)
                        )
                    }
                }

                NavGraph.apps[pkgName] = appGraph
            }

            Log.i(TAG, "Loaded: ${NavGraph.totalNodes()} nodes | " +
                    "${NavGraph.totalScreens()} screens | ${NavGraph.totalEdges()} edges")
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message}", e)
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    /**
     * Snapshot [NavGraph] on the calling thread, then write to [file] on Dispatchers.IO.
     *
     * Snapshots immutable copies of all collections before crossing thread boundaries,
     * so the live graph can keep mutating without any locking.
     */
    fun save(scope: CoroutineScope, file: File) {

        // ── Snapshot (main thread) ────────────────────────────────────────────
        val nodeSnap = NavGraph.nodes.toMap()

        data class EdgeSnap(val from: String, val to: String, val via: Long?, val count: Int)
        data class ScreenSnap(val root: String, val nodeIds: List<Long>)
        data class AppSnap(val screens: Map<String, ScreenSnap>, val edges: List<EdgeSnap>)

        val appsSnap = NavGraph.apps.mapValues { (_, app) ->
            AppSnap(
                screens = app.screens.mapValues { (_, s) -> ScreenSnap(s.rootName, s.nodeIds.toList()) },
                edges   = app.edges.map { EdgeSnap(it.fromScreen, it.toScreen, it.viaNodeId, it.count) }
            )
        }

        // ── Write (IO thread) ─────────────────────────────────────────────────
        scope.launch(Dispatchers.IO) {
            try {
                val root = JSONObject()
                root.put("version", VERSION)

                val stats = JSONObject()
                stats.put("nodes",   nodeSnap.size)
                stats.put("screens", appsSnap.values.sumOf { it.screens.size })
                stats.put("edges",   appsSnap.values.sumOf { it.edges.size })
                root.put("stats", stats)

                // Global nodes object  { "123456": "Bluetooth, Off", ... }
                val nodesObj = JSONObject()
                for ((id, name) in nodeSnap) nodesObj.put(id.toString(), name)
                root.put("nodes", nodesObj)

                // Apps
                val appsObj = JSONObject()
                for ((pkgName, app) in appsSnap) {
                    val appObj     = JSONObject()
                    val screensObj = JSONObject()

                    for ((rootName, screen) in app.screens) {
                        val screenObj = JSONObject()
                        screenObj.put("root", rootName)
                        val nodeArr = JSONArray()
                        screen.nodeIds.forEach { nodeArr.put(it) }
                        screenObj.put("nodes", nodeArr)
                        screensObj.put(rootName, screenObj)
                    }
                    appObj.put("screens", screensObj)

                    val edgesArr = JSONArray()
                    for (edge in app.edges) {
                        val edgeObj = JSONObject()
                        edgeObj.put("from",  edge.from)
                        edgeObj.put("to",    edge.to)
                        edgeObj.put("via",   edge.via ?: JSONObject.NULL)
                        edgeObj.put("count", edge.count)
                        edgesArr.put(edgeObj)
                    }
                    appObj.put("edges", edgesArr)
                    appsObj.put(pkgName, appObj)
                }
                root.put("apps", appsObj)

                file.writeText(root.toString(2), Charsets.UTF_8)
                Log.i(TAG, "Saved → ${file.name} " +
                        "(${file.length() / 1024}KB) | " +
                        "${nodeSnap.size} nodes | " +
                        "${appsSnap.values.sumOf { it.screens.size }} screens | " +
                        "${appsSnap.values.sumOf { it.edges.size }} edges")
            } catch (e: Exception) {
                Log.e(TAG, "Save failed: ${e.message}", e)
            }
        }
    }
}
