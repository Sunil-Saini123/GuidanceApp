package com.example.floatingassistant

import android.content.Context
import android.graphics.Rect
import android.util.Log
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
        // ── Layer 1: clean_page.json ──────────────────────────────────────────
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
        try {
            val db = NavGraphDatabase.getInstance(context)
            val screenId = "$activePackage::$activeScreenName"
            val allPkgTransitions = db.getTransitions(activePackage)
            
            for (t in allPkgTransitions) {
                if (t.fromScreenId == screenId || t.fromScreenId.contains(currStep, ignoreCase = true)) {
                    if (fuzzyElementMatch(t.actionLabel, nextStep)) {
                        Log.i(TAG, "Layer 2 (Local Graph): Found target '$nextStep' via action '${t.actionLabel}'")
                        // nav_graph doesn't store bounds directly in transitions currently, so return null bounds
                        return SearchResult(true, null)
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
     */
    fun hasScrollableContainer(): Boolean {
        val cleanPageFile = File(context.filesDir, "clean_page.json")
        if (cleanPageFile.exists()) {
            try {
                val jsonStr = cleanPageFile.readText()
                val root = JSONObject(jsonStr)
                val elements = root.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val cls = el.optString("class_name", "")
                    if (cls.contains("RecyclerView") || cls.contains("ScrollView") || cls.contains("ListView")) {
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
        
        return cleanEl.contains(cleanTarget) || cleanTarget.contains(cleanEl)
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
