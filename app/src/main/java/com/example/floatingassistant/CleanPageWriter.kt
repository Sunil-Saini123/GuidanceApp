package com.example.floatingassistant

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CleanPageWriter — Phase 5 / Tier 2
 *
 * Maintains the Clean Per-Page JSON file (Tier 2).
 *
 * Output path: /sdcard/Android/data/com.example.floatingassistant/files/ui_clean_page.json
 *
 * Output format:
 * ```json
 * {
 *   "root": "SettingsHomepage",
 *   "elements": [
 *     { "id": -83783365167035458, "name": "More connections" },
 *     { "id":  55820024708686460, "name": "Security & privacy" }
 *   ]
 * }
 * ```
 *
 * Lifecycle:
 *  - [write] with append=false → wipe and replace (called on navigation to a new/existing root).
 *  - [write] with append=true  → read existing file, merge new IDs, write back
 *                                 (called when scroll reveals new items on the SAME page).
 *
 * All IO runs on Dispatchers.IO via the service's scope. Writes are atomic (build string
 * in memory, single [File.writeText] call) to prevent partial-write corruption.
 */
object CleanPageWriter {

    private const val TAG   = "CleanPageWriter"
    const val FILE_NAME     = "ui_clean_page.json"

    /**
     * Write clean elements to the Tier 2 file.
     *
     * @param scope      CoroutineScope for background IO (use serviceScope).
     * @param outputFile Target file (ui_clean_page.json in external files dir).
     * @param rootName   Current contextual root name (e.g. "SettingsHomepage").
     * @param elements   Clean elements to write/append.
     * @param append     false = wipe and replace. true = merge with existing file.
     */
    fun write(
        scope: CoroutineScope,
        outputFile: File,
        rootName: String,
        elements: List<CleanPageExtractor.CleanElement>,
        append: Boolean
    ) {
        if (!append) {
            // ── Fresh write — replace everything ─────────────────────────────
            scope.launch(Dispatchers.IO) {
                try {
                    val json = buildJson(rootName, elements)
                    outputFile.writeText(json, Charsets.UTF_8)
                    Log.i(TAG, "Written (fresh) [$rootName] → ${elements.size} elements " +
                            "(${outputFile.length() / 1024}KB)")
                } catch (e: Exception) {
                    Log.e(TAG, "Fresh write failed: ${e.message}", e)
                }
            }
        } else {
            // ── Append — read existing, merge, write back ─────────────────────
            scope.launch(Dispatchers.IO) {
                try {
                    val (existingRoot, existingElements) = readExisting(outputFile)

                    val seenIds  = existingElements.mapTo(mutableSetOf()) { it.id }
                    val merged   = existingElements.toMutableList()
                    var appended = 0

                    for (el in elements) {
                        if (seenIds.add(el.id)) {
                            merged += el
                            appended++
                        }
                    }

                    if (appended > 0) {
                        val root = existingRoot.ifEmpty { rootName }
                        val json = buildJson(root, merged)
                        outputFile.writeText(json, Charsets.UTF_8)
                        Log.i(TAG, "Appended +$appended element(s) [$rootName] → ${merged.size} total")
                    } else {
                        Log.v(TAG, "Append skipped — 0 truly new clean elements for [$rootName]")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Append write failed: ${e.message}", e)
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun buildJson(rootName: String, elements: List<CleanPageExtractor.CleanElement>): String {
        val arr = JSONArray()
        for (el in elements) {
            val obj = JSONObject()
            obj.put("id",   el.id)
            obj.put("name", el.name)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("root",     rootName)
        root.put("elements", arr)
        return root.toString(2)
    }

    /**
     * Read and parse the existing Tier 2 file.
     * Returns an empty pair on missing / malformed file (safe to ignore).
     */
    private fun readExisting(
        file: File
    ): Pair<String, List<CleanPageExtractor.CleanElement>> {
        if (!file.exists() || file.length() == 0L) return "" to emptyList()
        return try {
            val json     = JSONObject(file.readText(Charsets.UTF_8))
            val rootName = json.optString("root", "")
            val arr      = json.optJSONArray("elements") ?: return rootName to emptyList()
            val elements = mutableListOf<CleanPageExtractor.CleanElement>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                elements += CleanPageExtractor.CleanElement(
                    id   = obj.getLong("id"),
                    name = obj.getString("name")
                )
            }
            rootName to elements
        } catch (e: Exception) {
            Log.w(TAG, "Could not parse existing file, treating as empty: ${e.message}")
            "" to emptyList()
        }
    }
}
