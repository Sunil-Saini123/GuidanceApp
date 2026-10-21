package com.example.floatingassistant

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ElementFinderEngine — Track 4 / Stage 3
 * Progressive Element Finder Pipeline
 */
class ElementFinderEngine(private val context: Context) {

    companion object {
        private const val TAG = "[NavigationEngine]"
    }

    data class SearchResult(val found: Boolean, val bounds: Rect? = null)

    /**
     * Searches for [nextStep] across 3 layers.
     * @return SearchResult with found status and optional bounds.
     */
    fun findElement(
        nextStep: String,
        activePackage: String,
        activeScreenName: String,
        currStep: String
    ): SearchResult {
        // [PERMANENT FIX] Do not remove this! Layer 0: Check Live Accessibility Tree in memory first.
        // This gives instant millisecond tracking without waiting for disk read.
        val liveBounds = findElementBoundsInLiveTree(nextStep)
        if (liveBounds != null) {
            Log.i(TAG, "Layer 0 (Live Memory Tree): Found target '$nextStep' immediately on screen")
            return SearchResult(true, liveBounds)
        }

        // ── Layer 1: clean_page.json (Internal Storage) ──────────────────────────
        // [PERMANENT FIX] Always read from context.filesDir (internal memory) matching UiTreeAccessibilityService
        val cleanPageFile = File(context.filesDir, "clean_page.json")
        if (cleanPageFile.exists()) {
            try {
                val jsonStr = cleanPageFile.readText()
                val root = JSONObject(jsonStr)
                val elements = root.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val name = el.optString("name", "")
                    val resId = el.optString("resource_id", "")
                    val cls = el.optString("class_name", "")
                    
                    if (fuzzyElementMatch(name, nextStep) ||
                        fuzzyElementMatch(resId, nextStep) ||
                        fuzzyElementMatch(cls, nextStep)) {
                        Log.i(TAG, "Layer 1 (Clean Page): Found target '$nextStep' in element (name='$name', res='$resId')")
                        return SearchResult(true, parseBounds(el.optJSONObject("bounds")))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Layer 1 search failed: ${e.message}")
            }
        }

        // ── Layer 2: Local Graph Scope (nav_graph.db) ─────────────────────────
        // The graph records historical transitions (e.g. "from Settings, About device was clicked").
        // However, OEM ROMs (ColorOS, MIUI) often report the SAME screen name for ALL sub-pages,
        // so a graph match alone is unreliable. We MUST verify the element is actually visible
        // on the current screen using the live accessibility tree.
        try {
            val db = NavGraphDatabase.getInstance(context)
            val screenId = "$activePackage::$activeScreenName"
            val allPkgTransitions = db.getTransitions(activePackage)
            
            for (t in allPkgTransitions) {
                if (t.fromScreenId == screenId || t.fromScreenId.contains(currStep, ignoreCase = true)) {
                    if (fuzzyElementMatch(t.actionLabel, nextStep)) {
                        // Graph says this element exists on this screen.
                        // Verify it's ACTUALLY visible in the live UI tree and get bounds.
                        val liveBounds = findElementBoundsInLiveTree(nextStep)
                        if (liveBounds != null) {
                            Log.i(TAG, "Layer 2 (Local Graph): Found target '$nextStep' via action '${t.actionLabel}' — verified visible")
                            return SearchResult(true, liveBounds)
                        } else {
                            Log.d(TAG, "Layer 2 (Local Graph): Graph says '$nextStep' exists via '${t.actionLabel}' but NOT visible on current screen — skipping")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Layer 2 search failed: ${e.message}")
        }

        // ── Layer 3: temp_tree.json (Raw Fallback) ────────────────────────────
        val tempTreeFile = File(context.filesDir, "temp_tree.json")
        if (tempTreeFile.exists()) {
            try {
                val jsonStr = tempTreeFile.readText()
                val root = JSONObject(jsonStr)
                val nodes = root.optJSONArray("nodes") ?: JSONArray()
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val text = node.optString("text", "")
                    val desc = node.optString("content_desc", "")
                    val resId = node.optString("resource_id", "")
                    
                    if (fuzzyElementMatch(text, nextStep) ||
                        fuzzyElementMatch(desc, nextStep) ||
                        fuzzyElementMatch(resId, nextStep)) {
                        Log.i(TAG, "Layer 3 (Raw Tree): Found target '$nextStep' in raw node (text='$text', desc='$desc', res='$resId')")
                        return SearchResult(true, parseBounds(node.optJSONObject("bounds")))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Layer 3 search failed: ${e.message}")
            }
        }

        return SearchResult(false, null)
    }

    /**
     * Checks if a scrollable container is currently on screen.
     * Prioritizes Layer 0 (live memory tree) with zero disk I/O, then falls back to clean_page/temp_tree.
     */
    fun hasScrollableContainer(): Boolean {
        // [PERMANENT FIX] Do not remove this! Layer 0: Check Live Accessibility Tree in RAM first.
        // Takes <1ms, performs 0 disk I/O, and accurately identifies native/Compose/OEM scroll containers.
        val service = UiTreeAccessibilityService.instance
        if (service != null) {
            val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant")
            if (root != null) {
                val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
                allocatedNodes.add(root)
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                try {
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        val cls = node.className?.toString().orEmpty()
                        if (node.isScrollable || 
                            cls.contains("RecyclerView", ignoreCase = true) || 
                            cls.contains("ScrollView", ignoreCase = true) || 
                            cls.contains("ListView", ignoreCase = true) ||
                            cls.contains("ViewPager", ignoreCase = true) ||
                            cls.contains("NestedScrollView", ignoreCase = true)) {
                            return true
                        }
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i)
                            if (child != null) {
                                allocatedNodes.add(child)
                                queue.add(child)
                            }
                        }
                    }
                } finally {
                    for (n in allocatedNodes) {
                        try { n.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }

        // Layer 1 & 2 fallback (if accessibility service root is briefly unavailable during window transition)
        val cleanPageFile = File(context.filesDir, "clean_page.json")
        if (cleanPageFile.exists()) {
            try {
                val jsonStr = cleanPageFile.readText()
                val root = JSONObject(jsonStr)
                val elements = root.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val cls = el.optString("class_name", "")
                    val isScrollable = el.optBoolean("is_scrollable", false)
                    if (isScrollable || cls.contains("RecyclerView") || cls.contains("ScrollView") || cls.contains("ListView")) {
                        return true
                    }
                }
            } catch (e: Exception) {}
        }
        
        val tempTreeFile = File(context.filesDir, "temp_tree.json")
        if (tempTreeFile.exists()) {
            try {
                val jsonStr = tempTreeFile.readText()
                val root = JSONObject(jsonStr)
                val nodes = root.optJSONArray("nodes") ?: JSONArray()
                for (i in 0 until nodes.length()) {
                    val node = nodes.optJSONObject(i) ?: continue
                    val isScrollable = node.optBoolean("is_scrollable", false)
                    val cls = node.optString("class_name", "")
                    if (isScrollable || cls.contains("RecyclerView") || cls.contains("ScrollView") || cls.contains("ListView")) {
                        return true
                    }
                }
            } catch (e: Exception) {}
        }
        return false
    }

    /**
     * Searches the live accessibility tree for an element matching [target].
     * Returns the element's screen bounds if found, null otherwise.
     */
    fun findElementBoundsInLiveTree(target: String): Rect? {
        val service = UiTreeAccessibilityService.instance ?: return null
        val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant") ?: return null

        val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
        allocatedNodes.add(root)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val resIdName = node.viewIdResourceName?.substringAfterLast("id/", "") ?: ""

                if (node.isVisibleToUser && (
                    fuzzyElementMatch(text, target) || 
                    fuzzyElementMatch(desc, target) || 
                    (text.isBlank() && desc.isBlank() && fuzzyElementMatch(resIdName, target)))) {
                    
                    // [PERMANENT FIX] Do not remove this! Universal row-block container expansion with Competing Sibling Action Protection.
                    // Expands compound rows (e.g. "Screen refresh rate" + "Standard >", chat list items) to the full row block,
                    // while strictly preserving independent action buttons (Cancel/OK, tabs, calculator grids, toolbar icons).
                    var targetNode = node
                    var currentBounds = Rect().also { node.getBoundsInScreen(it) }

                    // Protect standalone navigation icons (e.g. back arrow, close button)
                    val isDedicatedIcon = node.text.isNullOrBlank() && (
                        desc.equals("back", ignoreCase = true) ||
                        desc.equals("navigate up", ignoreCase = true) ||
                        desc.equals("close", ignoreCase = true)
                    )

                    if (!isDedicatedIcon) {
                        var currParent = node.parent
                        var depth = 0
                        while (currParent != null && depth < 5) {
                            allocatedNodes.add(currParent)
                            val pBounds = Rect()
                            currParent.getBoundsInScreen(pBounds)

                            // Stop if parent is too tall to be a single interactive row (height > 350px)
                            if (pBounds.height() > 350 || pBounds.height() <= 0) {
                                break
                            }

                            // Competing Sibling Action Check:
                            // Check if this parent has other independent, clickable action buttons
                            // (e.g. "Cancel" vs "OK", calculator number buttons, navigation tabs)
                            var hasCompetingSiblings = false
                            for (i in 0 until currParent.childCount) {
                                val child = currParent.getChild(i) ?: continue
                                allocatedNodes.add(child)
                                if (child == targetNode || child == node) continue

                                val cText = child.text?.toString()?.trim() ?: ""
                                val cDesc = child.contentDescription?.toString()?.trim() ?: ""
                                val isClickableAction = child.isClickable || child.isCheckable
                                val isDecorative = cText == ">" || cText == "›" || 
                                                   cDesc.contains("arrow", ignoreCase = true) || 
                                                   cDesc.contains("chevron", ignoreCase = true)

                                if (child.isVisibleToUser && isClickableAction && !isDecorative && (cText.isNotEmpty() || cDesc.isNotEmpty())) {
                                    hasCompetingSiblings = true
                                    break
                                }
                            }

                            if (hasCompetingSiblings) {
                                // Parent contains multiple distinct clickable choices — do NOT merge!
                                break
                            }

                            // If parent represents a wider unified block, expand candidate bounds
                            if (pBounds.width() > currentBounds.width()) {
                                targetNode = currParent
                                currentBounds = pBounds
                            }

                            // If this parent is explicitly clickable and spans the list row width, it is the master row container
                            if (currParent.isClickable && pBounds.width() >= 500) {
                                targetNode = currParent
                                currentBounds = pBounds
                                break
                            }

                            currParent = currParent.parent
                            depth++
                        }
                    }

                    // Extra sanity check: don't highlight the whole screen
                    if (currentBounds.height() > 500) {
                        node.getBoundsInScreen(currentBounds)
                    }

                    return if (currentBounds.width() > 0 && currentBounds.height() > 0) currentBounds else null
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        allocatedNodes.add(child)
                        queue.add(child)
                    }
                }
            }
            return null
        } finally {
            // [PERMANENT FIX] Do not remove this! Recycle all allocated AccessibilityNodeInfo objects to prevent native Binder buffer exhaustion (DeadObjectException)
            for (node in allocatedNodes) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }
    }

    private fun fuzzyElementMatch(elementStr: String, target: String): Boolean {
        if (elementStr.isBlank() || target.isBlank()) return false
        
        val cleanTarget = target.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanTarget.isEmpty()) return false
        
        var cleanEl = elementStr.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanEl.isEmpty()) return false
        
        val prefixes = listOf("originui", "bbk", "sec", "miui", "oppo", "vivo", "hw")
        for (prefix in prefixes) {
            if (cleanEl.startsWith(prefix)) {
                cleanEl = cleanEl.removePrefix(prefix)
            }
        }
        
        if (cleanEl == cleanTarget) return true
        if (cleanEl.contains(cleanTarget)) return true
        
        // [PERMANENT FIX] Do not remove length check!
        // Only allow reverse substring match if the element text is substantial (e.g. at least 5 chars)
        // to prevent matching minified resource IDs like "a", "b", "is", etc. which happen to be
        // substrings of the target text (e.g. "displaybrightness" contains "is").
        if (cleanEl.length >= 5 && cleanTarget.contains(cleanEl)) return true
        
        return false
    }
    
    private fun parseBounds(boundsObj: JSONObject?): Rect? {
        if (boundsObj == null) return null
        val w = boundsObj.optInt("width", -1)
        val h = boundsObj.optInt("height", -1)
        val cx = boundsObj.optInt("center_x", -1)
        val cy = boundsObj.optInt("center_y", -1)
        if (w > 0 && h > 0 && cx >= 0 && cy >= 0) {
            val left = cx - w / 2
            val top = cy - h / 2
            return Rect(left, top, left + w, top + h)
        }
        return null
    }
}
