package com.example.floatingassistant

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * RawDumpWriter — Phase 1 v2 (Universal OEM-Agnostic Pipeline)
 *
 * Maintains per-package accumulated state so that:
 *
 *   NAVIGATION event  → clears all state for the package, serialises the full
 *                        current window tree into a flat DFS-ordered node list,
 *                        and writes temp_tree.json fresh.
 *
 *   SCROLL / CONTENT  → traverses the current window tree, finds nodes whose
 *                        stable key is NOT already in seenKeys, appends them to
 *                        the accumulated list, and rewrites temp_tree.json.
 *                        If zero new nodes are found (scroll-up / no real change)
 *                        nothing is written — caller gets false back.
 *
 * Dedup key priority (most-specific first):
 *   1. resource_id                  (stable across scroll)
 *   2. text | class_name            (readable label)
 *   3. content_desc | class_name    (icon buttons etc.)
 *   4. bounds | class_name          (interactive-but-unnamed, e.g. custom FAB)
 *   5. null → skip entirely         (structural blanks with no identity)
 *
 * Output JSON (temp_tree.json):
 * {
 *   "meta": {
 *     "package_name": "com.whatsapp",
 *     "root_name":    "ConversationListActivity",
 *     "last_event":   "NAVIGATION" | "SCROLL" | "CONTENT_CHANGED",
 *     "timestamp":    1692039482123,
 *     "total_nodes":  247
 *   },
 *   "nodes": [          <- flat, DFS-ordered, deduplicated accumulated list
 *     { text, content_desc, resource_id, class_name, is_clickable,
 *       is_scrollable, is_enabled, is_visible_to_user, is_focusable,
 *       bounds, depth, child_count },
 *     ...
 *   ]
 * }
 *
 * Pull: adb pull /sdcard/Android/data/com.example.floatingassistant/files/temp_tree.json
 */
object RawDumpWriter {

    private const val TAG        = "RawDumpWriter"
    const val TEMP_FILE_NAME     = "temp_tree.json"
    private const val MAX_DEPTH  = 30

    // ── In-memory state ───────────────────────────────────────────────────────
    // Mutated only from the Main thread (service callbacks + Main.immediate coroutines).

    data class Snapshot(val nodes: List<JSONObject>, val rootName: String)

    private data class PackageState(
        val rootName: String,
        val seenKeys: HashSet<String>    = HashSet(),
        val nodes:    MutableList<JSONObject> = mutableListOf()
    )

    /** Master map: packageName → accumulated capture state. */
    private val stateMap = HashMap<String, PackageState>()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Called on every NAVIGATION event (TYPE_WINDOW_STATE_CHANGED).
     *
     * Clears all existing state for [packageName] and re-populates from the current
     * window tree.  Always writes temp_tree.json.
     *
     * Must be called on the main thread with [rootNode] still valid.
     * Does NOT recycle [rootNode] — caller owns lifecycle.
     */
    fun onNavigation(
        scope:       CoroutineScope,
        outputFile:  File,
        rootNode:    AccessibilityNodeInfo,
        packageName: String,
        rootName:    String
    ) {
        val state = PackageState(rootName = rootName)
        flattenInto(rootNode, state, depth = 0)
        // Supplemental: catch OEM virtual-view nodes invisible to getChild() DFS
        supplementalSearch(rootNode, state)
        stateMap[packageName] = state

        val snapshot = state.nodes.toList()
        flushToDisk(scope, outputFile, packageName, rootName, "NAVIGATION", snapshot)
        Log.i(TAG, "[NAVIGATION] $packageName/$rootName — ${snapshot.size} nodes")
    }

    /**
     * Called on SCROLL / CONTENT_CHANGED events (after debounce).
     *
     * Traverses [rootNode], finds nodes NOT yet in seenKeys, appends them.
     * Returns true if at least one new node was found (caller should trigger Phase 2).
     * Returns false if scroll-up or no real change — caller skips Phase 2 write.
     *
     * Must be called on the main thread with [rootNode] still valid.
     * Does NOT recycle [rootNode] — caller owns lifecycle.
     */
    fun onScroll(
        scope:       CoroutineScope,
        outputFile:  File,
        rootNode:    AccessibilityNodeInfo,
        packageName: String
    ): Boolean {
        val state = stateMap.getOrPut(packageName) {
            // First event for this package (no navigation seen yet) — treat as navigation
            PackageState(rootName = packageName.substringAfterLast('.'))
        }

        val newNodes = mutableListOf<JSONObject>()
        collectNew(rootNode, state, newNodes, depth = 0)
        // Supplemental search for any newly revealed virtual nodes after scroll
        supplementalSearch(rootNode, state, target = newNodes)

        return if (newNodes.isNotEmpty()) {
            state.nodes.addAll(newNodes)
            val snapshot = state.nodes.toList()
            flushToDisk(scope, outputFile, packageName, state.rootName, "SCROLL", snapshot)
            Log.i(TAG, "[SCROLL] $packageName/${state.rootName} +${newNodes.size} new " +
                    "(${state.nodes.size} total)")
            true
        } else {
            Log.v(TAG, "[SCROLL] $packageName — 0 new nodes, scroll-up ignored")
            false
        }
    }

    /**
     * Returns a snapshot of the current accumulated state for [packageName].
     * Used by the service to pass data directly to Phase 2 without a file round-trip.
     */
    fun getSnapshot(packageName: String): Snapshot? {
        val state = stateMap[packageName] ?: return null
        return Snapshot(state.nodes.toList(), state.rootName)
    }

    // ── Tree traversal ────────────────────────────────────────────────────────

    /**
     * DFS pre-order traversal: serialise [node] first, then recurse into children.
     * Nodes whose key is null (structural blanks) are NOT added to the list but
     * their children are still visited.
     */
    private fun flattenInto(node: AccessibilityNodeInfo, state: PackageState, depth: Int) {
        val key = computeKey(node)
        if (key != null && state.seenKeys.add(key)) {
            state.nodes.add(serializeNode(node, depth))
        }
        if (depth < MAX_DEPTH) {
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                try { flattenInto(child, state, depth + 1) }
                finally { child.recycle() }
            }
        } else {
            Log.w(TAG, "MAX_DEPTH ($MAX_DEPTH) reached at '${node.className}'")
        }
    }

    /**
     * DFS traversal collecting only nodes with keys NOT already in [state.seenKeys].
     * Same structural-blank handling as [flattenInto].
     */
    private fun collectNew(
        node: AccessibilityNodeInfo,
        state: PackageState,
        newNodes: MutableList<JSONObject>,
        depth: Int
    ) {
        val key = computeKey(node)
        if (key != null && state.seenKeys.add(key)) {
            newNodes.add(serializeNode(node, depth))
        }
        if (depth < MAX_DEPTH) {
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChild(i) ?: continue
                try { collectNew(child, state, newNodes, depth + 1) }
                finally { child.recycle() }
            }
        }
    }

    /**
     * Supplemental search for nodes that are invisible to standard getChild() DFS.
     *
     * Some OEM frameworks (Vivo OriginUI, Samsung OneUI, MIUI) implement their
     * preference/list items as VIRTUAL or CUSTOM views.  In these implementations
     * node.childCount reports > 0 but node.getChild(i) returns null because the
     * child is a virtual node managed by a custom AccessibilityNodeProvider.
     *
     * findAccessibilityNodeInfosByViewId() uses a completely different Android
     * framework code path (it queries the view hierarchy via reflection / provider
     * IPC) and CAN reach these nodes when getChild() cannot.
     *
     * Strategy:
     *   1. Search for "android:id/title" — the standard Android preference title ID
     *      used by Settings apps on nearly every OEM for the label next to each row.
     *   2. Search for "android:id/text1" — standard ListView/adapter primary text.
     *   3. Any node found that isn't already in seenKeys → add at depth = 0.
     *
     * depth = 0 is used as a sentinel so that Phase 2's container absorption
     * (which skips nodes at depth > parentDepth) can NEVER accidentally swallow
     * these supplemental nodes into a container.  They are always processed as
     * independent direct elements.
     *
     * @param rootNode  The root of the active window.
     * @param state     Current package state (seenKeys updated in-place).
     * @param target    If non-null, new nodes are added here (used by onScroll).
     *                  If null, new nodes are added directly to state.nodes.
     */
    private fun supplementalSearch(
        rootNode: AccessibilityNodeInfo,
        state:    PackageState,
        target:   MutableList<JSONObject>? = null
    ) {
        val SUPPLEMENT_IDS = listOf(
            "android:id/title",   // Settings preference title (Vivo, Samsung, MIUI, AOSP)
            "android:id/text1"    // ListView / RecyclerView adapter primary text
        )
        for (viewId in SUPPLEMENT_IDS) {
            val found = try {
                rootNode.findAccessibilityNodeInfosByViewId(viewId)
            } catch (e: Exception) {
                Log.w(TAG, "Supplemental search failed for $viewId: ${e.message}")
                continue
            } ?: continue

            for (child in found) {
                try {
                    // Only nodes with actual text are useful — empty ImageViews/etc. have title IDs too
                    val text = child.text?.toString()?.trim() ?: ""
                    if (text.isEmpty()) { child.recycle(); continue }

                    val key = computeKey(child)
                    if (key != null && state.seenKeys.add(key)) {
                        val node = serializeNode(child, depth = 0)
                        (target ?: state.nodes).add(node)
                        Log.v(TAG, "Supplemental: found '$text' via $viewId")
                    } else {
                        child.recycle()
                    }
                } catch (e: Exception) {
                    try { child.recycle() } catch (_: Exception) { }
                }
            }
        }
    }

    // ── Dedup key ─────────────────────────────────────────────────────────────

    /**
     * Resource IDs that are REUSED across many views on a single screen.
     *
     * Standard Android system IDs like `android:id/title` appear ONCE PER ROW
     * in every Settings preference list, RecyclerView adapter, ListView item, etc.
     * Using them as a unique identity key would cause every list item to be
     * treated as a duplicate of the first one captured, silently dropping all
     * subsequent items.
     *
     * For these IDs we skip the rid-based key and fall through to text-based
     * keying ("txt:$text|$className") which is unique per actual label.
     */
    private val GENERIC_SYSTEM_IDS = setOf(
        "android:id/title",
        "android:id/text1",
        "android:id/text2",
        "android:id/summary",
        "android:id/icon",
        "android:id/icon1",
        "android:id/icon2",
        "android:id/button1",
        "android:id/button2",
        "android:id/button3",
        "android:id/content",   // reused container ID
        "android:id/list",
        "android:id/empty",
        "android:id/message",
        "android:id/checkbox",
        "android:id/radio",
        "android:id/switch_widget",
        "android:id/widget_frame"
    )

    /**
     * Compute a stable identity key for [node].
     *
     * Returns null for structural nodes with absolutely no identity (not added to list).
     * The key is designed to be scroll-position-agnostic: same logical element →
     * same key regardless of whether the user has scrolled down or back up.
     * Exception: interactive-but-unnamed nodes use bounds because their position
     * is stable (e.g. a fixed FAB or system back button).
     *
     * NOTE: Generic system resource IDs (android:id/title, android:id/text1, etc.)
     * are SKIPPED for rid-based keying because they appear on every row in list
     * screens.  They fall through to text-based keying instead.
     */
    private fun computeKey(node: AccessibilityNodeInfo): String? {
        val rid  = node.viewIdResourceName?.trim()              ?: ""
        val text = node.text?.toString()?.trim()                ?: ""
        val cd   = node.contentDescription?.toString()?.trim()  ?: ""
        val cls  = node.className?.toString()?.trim()           ?: ""

        return when {
            // Use resource_id only when it is UNIQUE (app-specific IDs, named
            // elements).  Skip generic Android system IDs that are reused across
            // many rows in the same screen — those fall through to text keying.
            rid.isNotEmpty() && rid !in GENERIC_SYSTEM_IDS -> "rid:$rid"
            text.isNotEmpty() -> "txt:$text|$cls"
            cd.isNotEmpty()   -> "cd:$cd|$cls"
            node.isClickable || node.isScrollable -> {
                // Interactive but unnamed: key by screen position (fixed elements like FAB)
                val rect = Rect()
                node.getBoundsInScreen(rect)
                "pos:${rect.left}_${rect.top}_${rect.right}_${rect.bottom}|$cls"
            }
            else -> null   // pure structural blank — skip, but still recurse children
        }
    }


    // ── Serialisation ─────────────────────────────────────────────────────────


    private fun serializeNode(node: AccessibilityNodeInfo, depth: Int): JSONObject {
        val obj = JSONObject()

        obj.put("text",               node.text?.toString()               ?: "")
        obj.put("content_desc",       node.contentDescription?.toString() ?: "")
        obj.put("resource_id",        node.viewIdResourceName             ?: "")
        obj.put("class_name",         node.className?.toString()          ?: "")

        obj.put("is_clickable",       node.isClickable)
        obj.put("is_scrollable",      node.isScrollable)
        obj.put("is_enabled",         node.isEnabled)
        obj.put("is_visible_to_user", node.isVisibleToUser)
        obj.put("is_focusable",       node.isFocusable)
        obj.put("is_long_clickable",  node.isLongClickable)

        val rect = Rect()
        node.getBoundsInScreen(rect)
        val bounds = JSONObject()
        bounds.put("left",   rect.left)
        bounds.put("top",    rect.top)
        bounds.put("right",  rect.right)
        bounds.put("bottom", rect.bottom)
        bounds.put("width",  rect.width())
        bounds.put("height", rect.height())
        obj.put("bounds", bounds)

        obj.put("depth",       depth)
        obj.put("child_count", node.childCount)

        return obj
    }

    // ── Async disk write ──────────────────────────────────────────────────────

    private fun flushToDisk(
        scope:       CoroutineScope,
        outputFile:  File,
        packageName: String,
        rootName:    String,
        eventType:   String,
        nodes:       List<JSONObject>
    ) {
        val timestamp = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val root = JSONObject()

                val meta = JSONObject()
                meta.put("package_name", packageName)
                meta.put("root_name",    rootName)
                meta.put("last_event",   eventType)
                meta.put("timestamp",    timestamp)
                meta.put("total_nodes",  nodes.size)
                root.put("meta", meta)

                val arr = JSONArray()
                nodes.forEach { arr.put(it) }
                root.put("nodes", arr)

                outputFile.writeText(root.toString(2), Charsets.UTF_8)
                Log.i(TAG, "Written ${outputFile.name}: $packageName/$rootName " +
                        "(${nodes.size} nodes, ${outputFile.length() / 1024}KB)")
            } catch (e: Exception) {
                Log.e(TAG, "Write failed: ${e.message}", e)
            }
        }
    }
}
