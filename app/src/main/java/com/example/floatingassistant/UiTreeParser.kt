package com.example.floatingassistant

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Inbetween Filter / UiTreeParser — Phase 3
 *
 * Responsibility: convert a raw [AccessibilityNodeInfo] tree (delivered by the Accessibility
 * Service) into a tree of lightweight, JSON-friendly [UiNode] objects with FNV-1a hashed IDs.
 *
 * Processing pipeline for each node:
 *   1. Extract raw fields (text, contentDescription, resourceId, className, isClickable).
 *   2. Normalise them (strip empty, take simple class name).
 *   3. Build the hash key: "resourceId|text|className"  → FNV-1a 64-bit → [UiNode.nodeId].
 *   4. Recurse into children (depth-guarded at [MAX_DEPTH]).
 *   5. Recycle the [AccessibilityNodeInfo] obtained for each child to prevent OS-level
 *      object-pool leaks.
 *
 * Memory / performance notes:
 *  - Every [AccessibilityNodeInfo] obtained via [getChild] is recycled after we finish
 *    extracting data from it.  The root node is NOT recycled here — the caller owns it.
 *  - No intermediate String allocations on the hash path — [FnvHash.hash64of] hashes
 *    bytes in-place.
 *  - The recursion is bounded by [MAX_DEPTH] to protect against pathological UIs.
 */
object UiTreeParser {

    private const val TAG       = "InbetweenFilter"
    private const val DUMP_TAG  = "UiTreeDump"
    private const val MAX_DEPTH = 25   // Safety cap — typical Android UIs are 8-15 levels deep.

    /**
     * Parse the full subtree rooted at [root] into a [UiNode] tree.
     *
     * The caller retains ownership of [root] and must recycle it when done.
     *
     * @param root       Root [AccessibilityNodeInfo] (non-null, already passed MainFilter).
     * @param packageName Package that owns this window (used for logging only).
     * @return           Root [UiNode] of the parsed tree.
     */
    fun parse(root: AccessibilityNodeInfo, packageName: String): UiNode {
        val result = parseNode(root, depth = 0)
        Log.d(TAG, "Parsed '$packageName' → ${result.subtreeSize} nodes " +
                "(root childCount=${root.childCount})")
        return result
    }

    // ── Recursive worker ─────────────────────────────────────────────────────

    private fun parseNode(node: AccessibilityNodeInfo, depth: Int): UiNode {

        // ── 1. Extract raw fields ────────────────────────────────────────────
        val rawText        = node.text?.toString()?.trim()             ?: ""
        val rawContentDesc = node.contentDescription?.toString()?.trim() ?: ""
        val rawResourceId  = node.viewIdResourceName?.trim()           ?: ""
        val rawClassName   = node.className?.toString()?.trim()        ?: ""
        val isClickable    = node.isClickable

        // ── 2. Normalise ─────────────────────────────────────────────────────

        // Prefer explicit text; fall back to content description (e.g. icon buttons).
        val text = if (rawText.isNotEmpty()) rawText else rawContentDesc

        // Strip the package prefix from resourceId to save space:
        // "com.android.settings:id/wifi_toggle"  →  "wifi_toggle"
        val resourceId = if (rawResourceId.contains(":id/"))
            rawResourceId.substringAfterLast(":id/")
        else
            rawResourceId

        // Keep only the simple class name: "android.widget.TextView" → "TextView"
        val className = if (rawClassName.contains('.'))
            rawClassName.substringAfterLast('.')
        else
            rawClassName

        // ── 3. Parse children first (depth-guarded) ──────────────────────────
        val children: List<UiNode> = if (depth >= MAX_DEPTH) {
            Log.w(TAG, "Max depth $MAX_DEPTH reached at '$className' — subtree truncated")
            emptyList()
        } else {
            buildList {
                val count = node.childCount
                for (i in 0 until count) {
                    val child = node.getChild(i) ?: continue  // null if node is stale
                    try {
                        add(parseNode(child, depth + 1))
                    } finally {
                        // Recycle the child reference — we've already copied everything we need.
                        child.recycle()
                    }
                }
            }
        }

        // ── 4. Hash: stable composite key from content fields + children ──────
        // We deliberately exclude bounds/position so the same logical node always
        // gets the same ID regardless of scroll offset (needed for Phase 4 dedup).
        // To prevent collisions on generic containers (e.g. empty FrameLayout),
        // we fold in the hashes of all children.
        var nodeId = FnvHash.hash64of(resourceId, text, className)
        for (child in children) {
            // Mix child hash to distinguish containers with different children
            nodeId = (nodeId * 31) + child.nodeId
        }

        val bounds = android.graphics.Rect()
        node.getBoundsInScreen(bounds)

        return UiNode(
            nodeId      = nodeId,
            text        = text,
            resourceId  = resourceId,
            className   = className,
            isClickable = isClickable,
            boundsInScreen = bounds,
            children    = children
        )
    }

    // ── Tree dump (diagnostic) ────────────────────────────────────────────────

    /**
     * Log the full [UiNode] hierarchy to Logcat, one line per node.
     *
     * Uses tree-drawing characters (├──, └──, │  ) matching the flowchart style:
     * ```
     * [com.android.settings]
     * ├── Settings  [FrameLayout]
     * │   ├── Wi-Fi  (clickable)  [LinearLayout]  #wifi_item
     * │   ├── Bluetooth  (clickable)  [LinearLayout]  #bluetooth_item
     * │   │   ├── On/off  (clickable)  [Switch]  #bluetooth_toggle
     * │   │   └── Devices  [TextView]
     * │   └── Display  [LinearLayout]
     * ```
     *
     * Output tag: [DUMP_TAG] — filter with `adb logcat -s UiTreeDump` to read cleanly.
     *
     * Each line is emitted as a separate [Log.v] call to avoid the 4096-char
     * per-line Logcat truncation limit.
     */
    fun logTree(root: UiNode, packageName: String) {
        Log.v(DUMP_TAG, "┌─────────────────────────────────────────────")
        Log.v(DUMP_TAG, "│  [$packageName]  (${root.subtreeSize} nodes)")
        Log.v(DUMP_TAG, "│")
        dumpNodeLines(root, prefix = "", isLast = true)
        Log.v(DUMP_TAG, "└─────────────────────────────────────────────")
    }

    /**
     * Recursively emit one log line per node with proper tree-drawing connectors.
     *
     * @param node    Current node to render.
     * @param prefix  The indentation prefix inherited from the parent (e.g. "│   ").
     * @param isLast  Whether this node is the last child of its parent (affects connector char).
     */
    private fun dumpNodeLines(node: UiNode, prefix: String, isLast: Boolean) {
        val connector = if (isLast) "└── " else "├── "
        val childPrefix = prefix + if (isLast) "    " else "│   "

        // Build the display label in priority order: text > resourceId > className
        val label = when {
            node.text.isNotEmpty()       -> "\"${node.text}\""
            node.resourceId.isNotEmpty() -> "#${node.resourceId}"
            else                         -> "<${node.className}>"
        }

        val clickTag = if (node.isClickable) "  ✓clickable" else ""
        val idTag    = if (node.resourceId.isNotEmpty() && node.text.isNotEmpty())
            "  #${node.resourceId}" else ""
        val classTag = "  [${node.className}]"

        Log.v(DUMP_TAG, "$prefix$connector$label$clickTag$classTag$idTag")

        node.children.forEachIndexed { index, child ->
            dumpNodeLines(child, childPrefix, isLast = index == node.children.lastIndex)
        }
    }
}
