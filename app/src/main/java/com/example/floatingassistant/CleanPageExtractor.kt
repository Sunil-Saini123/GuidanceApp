package com.example.floatingassistant

import android.util.Log

/**
 * CleanPageExtractor — Phase 5 / Tier 2
 *
 * Transforms raw UiNode trees (Tier 1 data) into a hyper-clean, flat list of
 * actionable elements for the current page (Tier 2).
 *
 * As of Phase 11, the [items] passed to [extract] have already been through
 * [StaticDynamicFilter.classify], which strips dynamic content (contact names,
 * chat previews, timestamps, body text, etc.) before this walker runs. This
 * extractor therefore operates on a pre-cleaned list and its three rules apply
 * purely as a structural walk, not a content filter.
 *
 * Walk strategy (recursive DFS):
 *  ┌─ Rule 1: Node has non-empty text?
 *  │   → KEEP with name = text. STOP recursing (children are sub-representations).
 *  ├─ Rule 2: Node has no text, is clickable, is non-structural, has a meaningful rid?
 *  │   → KEEP with name = rid. STOP recursing.
 *  └─ Rule 3: Neither condition met (empty container / structural wrapper)?
 *      → SKIP this node, RECURSE into its children to find something meaningful.
 *
 * Deduplication:
 *  - By nodeId   : hard dedup — same logical node never appears twice.
 *  - By name text: semantic dedup — prevents the same label from two sibling branches.
 */
object CleanPageExtractor {

    private const val TAG = "CleanExtractor"

    // Pure layout/container classes — their rid is never a meaningful name.
    // Note: text still wins (Rule 1 fires first regardless of class).
    private val STRUCTURAL_CLASSES = setOf(
        "View", "ViewGroup", "FrameLayout", "LinearLayout", "RelativeLayout",
        "ConstraintLayout", "ScrollView", "NestedScrollView", "HorizontalScrollView",
        "RecyclerView", "ListView", "GridView", "CoordinatorLayout", "CardView",
        "ViewPager", "ViewPager2", "CollapsingToolbarLayout"
    )

    // Resource ID values that describe layout roles, not content.
    // UiTreeParser already strips the package prefix, so these are simple keys.
    private val STRUCTURAL_RIDS = setOf(
        "content", "container", "root", "layout", "item", "row", "cell",
        "icon", "image", "img", "list", "scroll", "frame", "wrapper", "holder",
        "parent", "child", "inner", "outer", "view", "panel", "background",
        "divider", "separator", "spacer", "tile", "card", "group",
        "dashboard_tile", "vlist_content", "home_dashboard_rootview",
        "dashboard_container", "account_category", "delete"
    )

    // ── Public API ────────────────────────────────────────────────────────────

    data class CleanElement(val id: Long, val name: String)

    /**
     * Extract clean, actionable elements from a list of raw UiNodes.
     *
     * @param items    Top-level content items from [SecondaryFilter.ScreenState].
     * @param seenIds  Mutable set of nodeIds already written to the Tier 2 file.
     *                 Pass the same instance across scroll-append calls to avoid
     *                 re-adding elements that are already in the file.
     */
    fun extract(
        items: List<UiNode>,
        seenIds: MutableSet<Long> = mutableSetOf()
    ): List<CleanElement> {
        val results   = mutableListOf<CleanElement>()
        val seenNames = mutableSetOf<String>()   // semantic dedup within this pass
        for (item in items) {
            walkNode(item, results, seenIds, seenNames)
        }
        Log.d(TAG, "Extracted ${results.size} clean elements from ${items.size} raw top-level items")
        return results
    }

    // ── Internal DFS walk ─────────────────────────────────────────────────────

    private fun walkNode(
        node: UiNode,
        results: MutableList<CleanElement>,
        seenIds: MutableSet<Long>,
        seenNames: MutableSet<String>
    ) {
        val text         = node.text.trim()
        // UiTreeParser already strips the package prefix from resourceId ("pkg:id/foo" → "foo")
        val rid          = node.resourceId.trim()
        val isStructural = node.className in STRUCTURAL_CLASSES

        when {
            // ── Rule 1: Has meaningful text ────────────────────────────────────
            // This is the gold-standard signal. Keep and stop.
            text.isNotEmpty() -> {
                if (seenIds.add(node.nodeId) && seenNames.add(text)) {
                    results += CleanElement(node.nodeId, text)
                }
                // Do NOT recurse — children are just sub-representations of the
                // same content (e.g. a child TextView repeating the parent's text).
            }

            // ── Rule 2: No text, but has a meaningful interactive rid ──────────
            // Catches clickable icon buttons, toggles, etc. with no visible label.
            !isStructural && node.isClickable &&
                    rid.isNotEmpty() && rid !in STRUCTURAL_RIDS -> {
                if (seenIds.add(node.nodeId)) {
                    results += CleanElement(node.nodeId, rid)
                }
                // Stop — this is the highest-meaningful node in this branch.
            }

            // ── Rule 3: Nothing useful here → dig deeper ───────────────────────
            else -> {
                for (child in node.children) {
                    walkNode(child, results, seenIds, seenNames)
                }
            }
        }
    }
}
