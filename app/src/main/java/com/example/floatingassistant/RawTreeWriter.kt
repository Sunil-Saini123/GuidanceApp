package com.example.floatingassistant

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * RawTreeWriter — Debug Phase (Raw Tree Extraction)
 *
 * Responsibility: serialize a COMPLETE, UNFILTERED [UiNode] tree to a uniquely-named
 * JSON file. One file is written per captured page so that every screen can be studied
 * independently without any frame overwriting another.
 *
 * ─── This file is ONLY active when [UiTreeAccessibilityService.DEBUG_SAVE_RAW_TREES]
 *     is true. No production pipeline code calls this writer. ───
 *
 * Output filename format:
 *   raw_page_<sanitized_package>_<epoch_seconds>.json
 *   e.g.  raw_page_com.whatsapp_1692039482.json
 *
 * Output location:  getExternalFilesDir(null) — same directory as all other tier files,
 *   pullable via:
 *     adb pull /sdcard/Android/data/com.example.floatingassistant/files/
 *
 * JSON structure (pretty-printed, 2-space indent):
 * ```json
 * {
 *   "meta": {
 *     "package": "com.whatsapp",
 *     "root_name": "ConversationListActivity",
 *     "event_type": "NAVIGATION",
 *     "timestamp_ms": 1692039482123,
 *     "total_nodes": 147
 *   },
 *   "tree": {
 *     "id": -3984756123456789,
 *     "text": "WhatsApp",
 *     "content_desc": "",
 *     "resource_id": "com.whatsapp:id/toolbar",
 *     "class": "Toolbar",
 *     "clickable": false,
 *     "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 196 },
 *     "children": [ ... ]
 *   }
 * }
 * ```
 *
 * Every field available in [UiNode] is serialized — nothing is omitted.
 * The `bounds` object is always written (all zeros when no bounds are available).
 */
object RawTreeWriter {

    private const val TAG = "RawTreeWriter"

    /**
     * Build a unique filename for this capture.
     *
     * Package name is sanitized: dots and colons replaced with underscores so the
     * filename is safe on all filesystems.
     *
     * @param packageName  The package that owns the captured window.
     * @param timestampMs  Epoch timestamp in milliseconds (use [System.currentTimeMillis]).
     * @return             e.g. "raw_page_com_whatsapp_1692039482.json"
     */
    fun buildFileName(packageName: String, timestampMs: Long): String {
        val safePkg = packageName.replace(Regex("[.:]"), "_")
        val epochSec = timestampMs / 1000L
        return "raw_page_${safePkg}_${epochSec}.json"
    }

    /**
     * Serialize [rootNode] to a uniquely-named JSON file under [outputDir] on an IO coroutine.
     *
     * Fire-and-forget: the caller does not need to await completion.
     *
     * @param scope       [CoroutineScope] to run IO work on (service scope is fine).
     * @param outputDir   Directory to write into (typically getExternalFilesDir(null)).
     * @param packageName Package that owns the captured window.
     * @param rootName    Contextual root name (Activity / Fragment class simple name).
     * @param rootNode    The full [UiNode] tree root (completely unfiltered).
     * @param eventType   Human-readable event label for the meta block ("NAVIGATION" / "SCROLL").
     */
    fun write(
        scope: CoroutineScope,
        outputDir: File,
        packageName: String,
        rootName: String,
        rootNode: UiNode,
        eventType: String
    ) {
        val timestampMs = System.currentTimeMillis()
        val fileName    = buildFileName(packageName, timestampMs)

        // Take a snapshot of the node count synchronously before the coroutine.
        val totalNodes  = rootNode.subtreeSize

        scope.launch(Dispatchers.IO) {
            try {
                val json = buildJson(
                    packageName = packageName,
                    rootName    = rootName,
                    eventType   = eventType,
                    timestampMs = timestampMs,
                    totalNodes  = totalNodes,
                    rootNode    = rootNode
                )
                val outFile = File(outputDir, fileName)
                outFile.writeText(json, Charsets.UTF_8)
                Log.i(TAG, "RAW DUMP → ${outFile.absolutePath}  " +
                        "(${outFile.length() / 1024}KB, $totalNodes nodes)")
            } catch (e: Exception) {
                Log.e(TAG, "Write failed for $fileName: ${e.message}", e)
            }
        }
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    private fun buildJson(
        packageName: String,
        rootName: String,
        eventType: String,
        timestampMs: Long,
        totalNodes: Int,
        rootNode: UiNode
    ): String {
        val root = JSONObject()

        // ── Meta block ────────────────────────────────────────────────────────
        val meta = JSONObject()
        meta.put("package",      packageName)
        meta.put("root_name",    rootName)
        meta.put("event_type",   eventType)
        meta.put("timestamp_ms", timestampMs)
        meta.put("total_nodes",  totalNodes)
        root.put("meta", meta)

        // ── Full tree block ───────────────────────────────────────────────────
        root.put("tree", serializeNode(rootNode))

        return root.toString(2)   // 2-space pretty-print — maximally readable
    }

    /**
     * Recursively serialize ONE [UiNode] into a [JSONObject].
     *
     * Every field in [UiNode] is included — nothing is hidden or omitted.
     * The `bounds` object is written even when [UiNode.boundsInScreen] is null
     * (all zeros in that case) so the schema is consistent.
     */
    private fun serializeNode(node: UiNode): JSONObject {
        val obj = JSONObject()

        // Stable hash ID (FNV-1a 64-bit)
        obj.put("id",           node.nodeId)

        // Human-readable text label (already set to contentDescription if text was empty)
        obj.put("text",         node.text)

        // Normalized resource-id (package prefix stripped)
        obj.put("resource_id",  node.resourceId)

        // Simple class name (e.g. "TextView", "RecyclerView")
        obj.put("class",        node.className)

        // Interaction flag
        obj.put("clickable",    node.isClickable)

        // Positional data — always present for completeness
        val b: Rect = node.boundsInScreen ?: Rect(0, 0, 0, 0)
        val boundsObj = JSONObject()
        boundsObj.put("left",   b.left)
        boundsObj.put("top",    b.top)
        boundsObj.put("right",  b.right)
        boundsObj.put("bottom", b.bottom)
        boundsObj.put("width",  b.width())
        boundsObj.put("height", b.height())
        obj.put("bounds", boundsObj)

        // Children — recursive; always written (empty array if leaf node)
        val childArr = JSONArray()
        for (child in node.children) {
            childArr.put(serializeNode(child))
        }
        obj.put("children", childArr)

        return obj
    }
}
