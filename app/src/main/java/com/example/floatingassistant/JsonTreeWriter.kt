package com.example.floatingassistant

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * JsonTreeWriter — Phase 4
 *
 * Responsibility: serialize the accumulated [SecondaryFilter.appStates] map to a
 * clean JSON file on a background IO coroutine.
 *
 * Output format:
 * ```json
 * {
 *   "com.android.settings": {
 *     "Settings": {
 *       "root": "Settings",
 *       "item_count": 15,
 *       "items": [
 *         {
 *           "id": 1234567890,
 *           "text": "Bluetooth",
 *           "class": "FrameLayout",
 *           "rid": "bluetooth_item",
 *           "click": true,
 *           "children": [...]
 *         }
 *       ]
 *     }
 *   }
 * }
 * ```
 *
 * Performance:
 *  - All serialization runs on [Dispatchers.IO] — never blocks the main thread.
 *  - Uses [CoroutineScope] from the service (already alive), not its own scope.
 *  - Writes are atomic: builds the full JSON string in memory, then does a single
 *    [File.writeText] call to minimize partial-write corruption.
 *  - File is placed in [Context.cacheDir] which is wiped on uninstall and never
 *    backed up — appropriate for temporary session data.
 */
object JsonTreeWriter {

    private const val TAG      = "JsonTreeWriter"
    const val FILE_NAME        = "ui_tree_temp.json"

    /**
     * Serialize and write the full accumulated state to [outputFile].
     *
     * This is a fire-and-forget coroutine launch — the caller does not need to await it.
     *
     * @param scope      [CoroutineScope] to launch the IO work on (use the service scope).
     * @param outputFile Target JSON file (should be in cacheDir).
     * @param appStates  The live accumulation map from [SecondaryFilter.appStates].
     */
    fun write(
        scope: CoroutineScope,
        outputFile: File,
        appStates: Map<String, SecondaryFilter.AppState>
    ) {
        // Take an immutable snapshot of the state map before launching the coroutine
        // so we don't hold a reference to mutable collections across thread boundaries.
        val snapshot = appStates.mapValues { (_, appState) ->
            appState.screens.mapValues { (_, screenState) ->
                screenState.rootName to screenState.items.toList()
            }
        }

        scope.launch(Dispatchers.IO) {
            try {
                val json = buildJson(snapshot)
                outputFile.writeText(json, Charsets.UTF_8)
                Log.i(TAG, "Written → ${outputFile.absolutePath}  " +
                        "(${outputFile.length() / 1024}KB)")
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}", e)
            }
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun buildJson(
        snapshot: Map<String, Map<String, Pair<String, List<UiNode>>>>
    ): String {
        val root = JSONObject()

        for ((packageName, screens) in snapshot) {
            val pkgObj = JSONObject()

            for ((rootName, pair) in screens) {
                val (_, items) = pair
                val screenObj  = JSONObject()
                screenObj.put("root",       rootName)
                screenObj.put("item_count", items.size)
                screenObj.put("items",      serializeNodes(items))
                pkgObj.put(rootName, screenObj)
            }

            root.put(packageName, pkgObj)
        }

        return root.toString(2)   // pretty-print with 2-space indent
    }

    private fun serializeNodes(nodes: List<UiNode>): JSONArray {
        val arr = JSONArray()
        for (node in nodes) arr.put(serializeNode(node))
        return arr
    }

    private fun serializeNode(node: UiNode): JSONObject {
        val obj = JSONObject()
        obj.put("id",       node.nodeId)
        obj.put("text",     node.text)
        obj.put("class",    node.className)
        obj.put("rid",      node.resourceId)
        obj.put("click",    node.isClickable)
        if (node.children.isNotEmpty()) {
            obj.put("children", serializeNodes(node.children))
        }
        return obj
    }
}
