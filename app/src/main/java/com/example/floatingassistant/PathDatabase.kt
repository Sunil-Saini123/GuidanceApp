package com.example.floatingassistant

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * PathDatabase — Phase 7
 *
 * Looks up a stored navigation path for a free-text user intent query, scoped
 * to the current device's signature: [Manufacturer/Model] → [Android/OS version].
 *
 * Mirrors the flow sketched in the design doc:
 *  1. Search Path                          → [lookup] entry point
 *  2. "Has anyone done this before?"        → does a bucket exist for [signature][osVersion]?
 *  3. "Same Phone Same Version Same OS?"    → the bucket IS the exact-signature match;
 *                                             a different device's data is never used.
 *  4. Fallback                             → no match → "" + log "Path not found"
 *
 * "Check Online" (remote lookup for a different device / no local match) is
 * intentionally NOT implemented in this phase — see PROGRESS.md.
 *
 * Storage (persisted at `<externalFilesDir>/path_database.json`):
 * ```json
 * {
 *   "samsung_sm-s911b": {
 *     "14": [
 *       {
 *         "keywords": ["change", "whatsapp", "dp", "profile", "picture"],
 *         "path": "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile"
 *       }
 *     ]
 *   }
 * }
 * ```
 *
 * Call sites should invoke [lookup] / [addEntry] off the main thread
 * (e.g. from `Dispatchers.IO`) — this object performs synchronous file I/O
 * on first access and on every [addEntry].
 */
object PathDatabase {

    private const val TAG = "PathDatabase"
    const val FILE_NAME = "path_database.json"

    data class Entry(val keywords: List<String>, val path: String)

    // signature -> osVersion -> entries
    private val db: MutableMap<String, MutableMap<String, MutableList<Entry>>> = mutableMapOf()
    private var loaded = false

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Resolve [query] against the current device's stored paths.
     *
     * @return the matched path string (e.g. "WhatsApp -> 3 dots -> ...") or
     *         "" if no path exists for this device signature or this query.
     */
    fun lookup(context: Context, query: String): String {
        ensureLoaded(context)

        val (signature, osVersion) = currentDeviceKey()
        val bucket = db[signature]?.get(osVersion)

        // "Has anyone done this before?" → No bucket for this exact device+OS.
        if (bucket.isNullOrEmpty()) {
            Log.w(TAG, "Path not found")
            Log.d(TAG, "  (no entries for signature=$signature, os=$osVersion)")
            return ""
        }

        // "Same Phone Same Version Same OS" bucket exists → keyword-match within it.
        val queryTokens = tokenize(query)
        var best: Entry? = null
        var bestScore = 0

        for (entry in bucket) {
            val score = entry.keywords.count { it in queryTokens }
            if (score > bestScore) {
                bestScore = score
                best = entry
            }
        }

        if (best == null || bestScore == 0) {
            Log.w(TAG, "Path not found")
            Log.d(TAG, "  (no keyword match for query=\"$query\" in signature=$signature, os=$osVersion)")
            return ""
        }

        Log.i(TAG, "Resolved \"$query\" → \"${best.path}\" (score=$bestScore, $signature/$osVersion)")
        return best.path
    }

    /** Add/overwrite a path entry for the CURRENT device signature + OS version, and persist. */
    fun addEntry(context: Context, keywords: List<String>, path: String) {
        ensureLoaded(context)
        val (signature, osVersion) = currentDeviceKey()
        val osBucket = db.getOrPut(signature) { mutableMapOf() }
        val list = osBucket.getOrPut(osVersion) { mutableListOf() }
        list += Entry(keywords.map { it.lowercase(Locale.US) }, path)
        persist(context)
    }

    // ── Device signature ──────────────────────────────────────────────────────

    /**
     * Builds the [signature, osVersion] key pair for the current device.
     * signature = "<manufacturer>_<model>" (lowercased, whitespace → underscores)
     * osVersion = Build.VERSION.RELEASE (falls back to SDK int as a string)
     */
    private fun currentDeviceKey(): Pair<String, String> {
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val signature = "${manufacturer}_$model"
            .lowercase(Locale.US)
            .replace(Regex("\\s+"), "_")
        val osVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        return signature to osVersion
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        loaded = true

        val file = dbFile(context)
        if (!file.exists()) {
            seedDefaults(context)
            return
        }

        try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            root.keys().forEach { signature ->
                val osObj = root.getJSONObject(signature)
                val osMap = db.getOrPut(signature) { mutableMapOf() }
                osObj.keys().forEach { osVersion ->
                    val arr = osObj.getJSONArray(osVersion)
                    val list = osMap.getOrPut(osVersion) { mutableListOf() }
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        val kwArr = obj.getJSONArray("keywords")
                        val keywords = (0 until kwArr.length()).map { kwArr.getString(it) }
                        list += Entry(keywords, obj.getString("path"))
                    }
                }
            }
            Log.i(TAG, "Loaded ${file.name}: ${db.size} device signature(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Load failed: ${e.message} — starting with seed data", e)
            db.clear()
            seedDefaults(context)
        }
    }

    /** Seeds one example entry (matches the diagram's WhatsApp DP-change flow) so the module is testable out of the box. */
    private fun seedDefaults(context: Context) {
        addEntry(
            context = context,
            keywords = listOf("change", "whatsapp", "dp", "profile", "picture", "photo"),
            path = "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile"
        )
    }

    private fun persist(context: Context) {
        try {
            val root = JSONObject()
            for ((signature, osMap) in db) {
                val osObj = JSONObject()
                for ((osVersion, entries) in osMap) {
                    val arr = JSONArray()
                    for (entry in entries) {
                        val obj = JSONObject()
                        obj.put("keywords", JSONArray(entry.keywords))
                        obj.put("path", entry.path)
                        arr.put(obj)
                    }
                    osObj.put(osVersion, arr)
                }
                root.put(signature, osObj)
            }
            dbFile(context).writeText(root.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Persist failed: ${e.message}", e)
        }
    }

    private fun dbFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }
}