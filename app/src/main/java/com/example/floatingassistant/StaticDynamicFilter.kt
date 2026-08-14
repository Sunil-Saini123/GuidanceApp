package com.example.floatingassistant

import android.content.res.Resources
import android.util.Log

/**
 * StaticDynamicFilter — Phase 11 v4 (Improved UI Filter)
 *
 * Implements a 4-step filtering engine using a centralized keyword list,
 * positional heuristics (bounds), and structural rules.
 */
object StaticDynamicFilter {

    private const val TAG = "StaticDynFilter"

    // Screen height for Step 2 positional check
    private val SCREEN_HEIGHT = Resources.getSystem().displayMetrics.heightPixels

    private const val MAX_APP_ICON_LEN = 25
    private const val MAX_CATCH_ALL_LEN = 25
    private const val MAX_TEXT_LEN = 35

    // Step 3 Regexes
    private val REGEX_TIME = Regex("""\b\d{1,2}:\d{2}\b""")
    private val REGEX_DATE = Regex("""\b\d{1,2}/\d{1,2}/\d{2,4}\b""")
    private val REGEX_RELATIVE_TIME = Regex(
        """(?i)\d+\s*(hours?|mins?|minutes?|seconds?|days?|weeks?|months?|years?)\s*ago"""
    )
    private val REGEX_METRIC = Regex(
        """(?i)\d+(\.\d+)?[KMGT]?\s*(unread|views?|likes?|subscribers?)"""
    )
    private val REGEX_STATUS_MEDIA = Regex(
        """(?i)(-\sgo to channel|-\splay video|,\sunread status)"""
    )

    private val IMPORTANT_CLASSES = setOf(
        "ImageButton", "Chip", "FloatingActionButton", "TabLayout", "BottomNavigationView"
    )

    fun classify(items: List<UiNode>, packageName: String = ""): List<UiNode> {
        val result = mutableListOf<UiNode>()
        var keptCount = 0
        var droppedCount = 0

        for (item in items) {
            val filtered = filterNode(item, packageName)
            if (filtered != null) {
                result += filtered
                keptCount++
            } else {
                droppedCount++
            }
        }

        Log.d(TAG, "classify [$packageName]: kept=$keptCount dropped=$droppedCount of ${items.size}")
        return result
    }

    private fun filterNode(node: UiNode, packageName: String): UiNode? {
        // ── Step 1: Basic Info ────────────────────────────────────────────────
        // Prioritize text (which contains contentDescription as fallback) over resourceId.
        val unifiedName = node.text.ifEmpty { node.resourceId }.trim()

        if (unifiedName.isEmpty() && !node.isClickable) {
            if (node.children.isEmpty()) {
                Log.v(TAG, "S1 DROP (empty + not clickable): cls=${node.className}")
                return null
            } else {
                // Structural pass-through for nameless containers so we don't break the tree
                val filteredChildren = node.children.mapNotNull { filterNode(it, packageName) }
                return if (filteredChildren.isEmpty()) null else node.copy(children = filteredChildren)
            }
        }

        // We process children first to build the new subtree
        val filteredChildren = node.children.mapNotNull { filterNode(it, packageName) }

        // ── Step 2: Priority KEEP (Whitelist) ─────────────────────────────────
        if (step2Keep(node, unifiedName, packageName)) {
            Log.v(TAG, "S2 KEEP: \"${unifiedName.take(40)}\" cls=${node.className}")
            return node.copy(children = filteredChildren)
        }

        // ── Step 3: Priority DROP (Blacklist) ─────────────────────────────────
        if (step3Drop(unifiedName)) {
            // Reason is logged inside step3Drop
            return null
        }

        // ── Step 4: The Final Catch-All ───────────────────────────────────────
        if (node.isClickable && unifiedName.length <= MAX_CATCH_ALL_LEN) {
            Log.v(TAG, "S4 KEEP (Catch-All): \"${unifiedName.take(40)}\"")
            return node.copy(children = filteredChildren)
        }

        // If the node itself is dropped by Catch-All, but it has surviving children,
        // we act as a pass-through container to preserve the children.
        if (filteredChildren.isNotEmpty()) {
            return node.copy(text = "", resourceId = "", children = filteredChildren)
        }

        Log.v(TAG, "S4 DROP (Catch-All fallback): \"${unifiedName.take(40)}\"")
        return null
    }

    private fun step2Keep(node: UiNode, unifiedName: String, packageName: String): Boolean {
        // 1. Wordlist match
        if (UIKeywords.matches(unifiedName)) return true

        val bounds = node.boundsInScreen
        if (bounds != null) {
            // 2. Top/Bottom Bars
            if (node.isClickable) {
                val centerY = bounds.centerY()
                val top15 = SCREEN_HEIGHT * 0.15
                val bottom15 = SCREEN_HEIGHT * 0.85
                if (centerY <= top15 || centerY >= bottom15) return true
            }

            // 3. Square Icons/Buttons
            if (node.isClickable) {
                val w = bounds.width()
                val h = bounds.height()
                if (w in 1..250 && h in 1..250) {
                    val ratio = w.toFloat() / h.toFloat()
                    if (ratio in 0.7f..1.3f) return true
                }
            }
        }

        // 4. Important UI Classes
        val simpleClass = simpleClassName(node.className)
        if (IMPORTANT_CLASSES.any { simpleClass.contains(it, ignoreCase = true) }) return true

        // 5. App Icons (Home Screen)
        if (packageName.contains("launcher", ignoreCase = true) &&
            node.isClickable &&
            unifiedName.length <= MAX_APP_ICON_LEN
        ) {
            return true
        }

        return false
    }

    private fun step3Drop(unifiedName: String): Boolean {
        // 1. Too Long
        if (unifiedName.contains('\n') || unifiedName.length > MAX_TEXT_LEN) {
            Log.v(TAG, "S3 DROP (Too Long/Multi-line): len=${unifiedName.length}")
            return true
        }

        // 2. Dynamic Numbers/Dates
        if (REGEX_TIME.containsMatchIn(unifiedName) ||
            REGEX_DATE.containsMatchIn(unifiedName) ||
            REGEX_RELATIVE_TIME.containsMatchIn(unifiedName)
        ) {
            Log.v(TAG, "S3 DROP (Time/Date): \"$unifiedName\"")
            return true
        }

        // 3. Dynamic Metrics
        if (REGEX_METRIC.containsMatchIn(unifiedName)) {
            Log.v(TAG, "S3 DROP (Metric): \"$unifiedName\"")
            return true
        }

        // 4. Status/Media Sentences
        if (REGEX_STATUS_MEDIA.containsMatchIn(unifiedName)) {
            Log.v(TAG, "S3 DROP (Status/Media): \"$unifiedName\"")
            return true
        }

        return false
    }

    private fun simpleClassName(className: String): String =
        className.substringAfterLast('.')
}
