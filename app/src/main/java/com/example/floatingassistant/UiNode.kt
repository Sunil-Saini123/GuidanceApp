package com.example.floatingassistant

/**
 * UiNode — lightweight, JSON-friendly representation of one accessibility node.
 *
 * Designed to be cheap to create and easy to serialize.  All fields are immutable.
 *
 * @param nodeId      FNV-1a 64-bit hash of (resourceId | text | className).
 *                    Used as a stable identity key for deduplication in Phase 4.
 * @param text        The human-readable label: [AccessibilityNodeInfo.text] if non-empty,
 *                    otherwise [AccessibilityNodeInfo.contentDescription].
 *                    Empty string when both are absent.
 * @param resourceId  The view's resource-id string, e.g. "com.android.settings:id/title".
 *                    Empty string when the node has no resource ID.
 * @param className   Simple class name, e.g. "TextView", "RecyclerView".
 *                    Full class name is stripped to the last segment to save space.
 * @param isClickable Whether this node (or any ancestor that owns the click area) is
 *                    marked clickable.  Explicitly captured as required by the spec.
 * @param children    Direct child [UiNode]s in document order (top-to-bottom, left-to-right).
 */
import android.graphics.Rect

data class UiNode(
    val nodeId: Long,
    val text: String,
    val resourceId: String,
    val className: String,
    val isClickable: Boolean,
    val boundsInScreen: Rect?,
    val children: List<UiNode>
) {
    /**
     * Total number of nodes in the subtree rooted at this node (including self).
     * Computed lazily; useful for logging and capacity planning.
     */
    val subtreeSize: Int by lazy {
        1 + children.sumOf { it.subtreeSize }
    }
}
