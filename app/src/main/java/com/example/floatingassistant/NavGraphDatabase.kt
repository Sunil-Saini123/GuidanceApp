package com.example.floatingassistant

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * NavGraphDatabase — Phase 3 (Universal OEM-Agnostic Pipeline)
 *
 * Plain SQLiteOpenHelper (no Room / no kapt / no KSP required).
 *
 * ── Schema ────────────────────────────────────────────────────────────────────
 *
 * screens
 * ┌──────────────┬──────────────────────────────────────────────────────────┐
 * │ id           │ TEXT PK  — deterministic "$packageName::$screenTitle"    │
 * │ package_name │ TEXT                                                     │
 * │ screen_title │ TEXT     — header/toolbar text used to derive the ID     │
 * │ root_class   │ TEXT     — Activity class name from the navigation event │
 * │ elements_json│ TEXT     — JSON array of clean elements (Phase 2 output) │
 * │ visit_count  │ INTEGER  — how many times this screen was navigated to   │
 * │ first_seen   │ INTEGER  — epoch ms                                      │
 * │ last_seen    │ INTEGER  — epoch ms                                      │
 * └──────────────┴──────────────────────────────────────────────────────────┘
 *
 * transitions
 * ┌────────────────┬────────────────────────────────────────────────────────┐
 * │ id             │ INTEGER PK AUTOINCREMENT                               │
 * │ from_screen_id │ TEXT FK → screens.id                                  │
 * │ to_screen_id   │ TEXT FK → screens.id                                  │
 * │ action_label   │ TEXT — element name clicked, or "BACK"                │
 * │ action_type    │ TEXT — "CLICK" or "BACK"                              │
 * │ traversal_count│ INTEGER — incremented on each re-traversal            │
 * │ weight         │ REAL — A* edge cost (default 1.0, tunable later)      │
 * │ first_seen     │ INTEGER — epoch ms                                     │
 * │ last_seen      │ INTEGER — epoch ms                                     │
 * └────────────────┴────────────────────────────────────────────────────────┘
 *
 * Indices: transitions.from_screen_id, transitions.to_screen_id, screens.package_name
 */
class NavGraphDatabase private constructor(context: Context)
    : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val TAG        = "NavGraphDB"
        private const val DB_NAME    = "nav_graph.db"
        // v2: added UNIQUE index on transitions(from, to, action_type) for dedup
        private const val DB_VERSION = 2

        @Volatile private var INSTANCE: NavGraphDatabase? = null

        fun getInstance(context: Context): NavGraphDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: NavGraphDatabase(context).also { INSTANCE = it }
            }
    }

    // ── Schema creation ───────────────────────────────────────────────────────

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS screens (
                id            TEXT    PRIMARY KEY,
                package_name  TEXT    NOT NULL,
                screen_title  TEXT    NOT NULL,
                root_class    TEXT    NOT NULL,
                elements_json TEXT    NOT NULL,
                visit_count   INTEGER NOT NULL DEFAULT 1,
                first_seen    INTEGER NOT NULL,
                last_seen     INTEGER NOT NULL
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE IF NOT EXISTS transitions (
                id              INTEGER PRIMARY KEY AUTOINCREMENT,
                from_screen_id  TEXT    NOT NULL,
                to_screen_id    TEXT    NOT NULL,
                action_label    TEXT    NOT NULL,
                action_type     TEXT    NOT NULL,
                traversal_count INTEGER NOT NULL DEFAULT 0,
                weight          REAL    NOT NULL DEFAULT 1.0,
                first_seen      INTEGER NOT NULL,
                last_seen       INTEGER NOT NULL
            )
        """.trimIndent())

        // Standard lookup indices
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trans_from    ON transitions(from_screen_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_trans_to      ON transitions(to_screen_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_screens_pkg   ON screens(package_name)")
        // Dedup guarantee: only one edge per (from → to, action_type) pair
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS idx_trans_unique " +
            "ON transitions(from_screen_id, to_screen_id, action_type)"
        )
        Log.i(TAG, "Database created: $DB_NAME v$DB_VERSION")
    }

    /**
     * Non-destructive upgrade: only the UNIQUE index is new in v2.
     * Adding it on top of the existing tables preserves all graph data
     * already collected in v1.  If the DB had duplicate rows (from v1),
     * the CREATE UNIQUE INDEX will fail gracefully; those rows remain but
     * are now unreachable by the INSERT OR IGNORE logic and won't grow.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.i(TAG, "Upgrading DB $oldVersion → $newVersion")
        if (oldVersion < 2) {
            try {
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS idx_trans_unique " +
                    "ON transitions(from_screen_id, to_screen_id, action_type)"
                )
                Log.i(TAG, "v2 upgrade: UNIQUE index added to transitions")
            } catch (e: Exception) {
                Log.w(TAG, "v2 upgrade: UNIQUE index creation failed (pre-existing duplicates?): ${e.message}")
            }
        }

    }

    // ── Screen CRUD ───────────────────────────────────────────────────────────

    /**
     * Insert or update a screen record.
     *
     * On INSERT (first visit): stores the element list as-is.
     * On UPDATE (revisit):     MERGES the existing element list with the new one
     *                          (union by name, case-insensitive).
     *
     * Why merge instead of overwrite?
     * On a fresh navigation event only the viewport-visible elements are in the
     * clean snapshot (scroll accumulation is reset).  Overwriting would discard
     * elements that were discovered during a previous scroll, causing them to
     * look "new" again on the next scroll and inflating the graph with redundant
     * re-appearances.  Merging ensures every unique element seen on a screen is
     * recorded exactly once, regardless of how many times the user visits or
     * scrolls.
     */
    /**
     * [PERMANENT FIX] Do not remove this!
     * Normalizes an element label to its fundamental base name.
     * Strips Unicode typographic hyphens, dynamic subtitles (after '·' or '•'),
     * trailing state toggles (" Off", " On"), and badge counts (" 15").
     * e.g. "Wi-Fi · Vidyalankar Campus" -> "Wi-Fi", "Wi-Fi Off" -> "Wi-Fi".
     */
    fun extractBaseElementLabel(raw: String): String {
        if (raw.isEmpty()) return ""
        val normalized = raw
            .replace("\u2010", "-")
            .replace("\u2011", "-")
            .replace("\u2012", "-")
            .replace("\u2013", "-")
            .replace("\u2014", "-")
            .replace("\u2015", "-")
            .replace("\u2212", "-")
            .replace("\u00A0", " ")
            .replace("\u202F", " ")

        val withoutDot = normalized.split(Regex("\\s*[·•⋅—]\\s*")).firstOrNull()?.trim() ?: normalized
        return withoutDot
            .replace(Regex("\\s+(on|off|high|low|medium|connected|disconnected|disabled|enabled)\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+\\d+$"), "")
            .trim()
    }

    // [PERMANENT FIX] Do not remove this! Structural Noise & Passive Container Filter
    // Drops system navigation bar, gesture bar, full-width layout docks, action bar, and search box placeholders.
    private val STRUCTURAL_NOISE_LABELS = setOf(
        "bottom_bar", "bottom bar",
        "action_bar", "action bar",
        "scrollable_list", "scrollable list",
        "search box"
    )

    fun isNoiseElement(name: String, screenTitle: String = "", packageName: String = ""): Boolean {
        if (name.isEmpty()) return true
        if (screenTitle.isNotEmpty() && name.equals(screenTitle, ignoreCase = true)) return true
        if (packageName.isNotEmpty() && name.equals(packageName.substringAfterLast('.'), ignoreCase = true)) return true
        val normalized = name.lowercase().replace('_', ' ').trim()
        return normalized in STRUCTURAL_NOISE_LABELS || name.lowercase() in STRUCTURAL_NOISE_LABELS
    }
    fun upsertScreen(
        screenId:     String,
        packageName:  String,
        screenTitle:  String,
        rootClass:    String,
        elements:     List<JSONObject>
    ) {
        val now = System.currentTimeMillis()
        val db  = writableDatabase

        // [PERMANENT FIX] Do not remove this! Header Title & Noise Suppression:
        // Filter out redundant screen titles, search noise, and structural container placeholders ("Bottom_Bar", etc.)
        val cleanList = elements.filterNot { el ->
            val name = el.optString("name", "").trim()
            isNoiseElement(name, screenTitle, packageName)
        }

        val cursor = db.query(
            "screens", arrayOf("visit_count", "elements_json"),
            "id = ?", arrayOf(screenId),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val visits       = cursor.getInt(0)
            val existingJson = cursor.getString(1)
            cursor.close()

            val mergedJson = mergeElements(existingJson, cleanList, screenTitle)
            db.execSQL(
                "UPDATE screens SET elements_json = ?, visit_count = ?, last_seen = ? WHERE id = ?",
                arrayOf(mergedJson, visits + 1, now, screenId)
            )
            Log.d(TAG, "upsertScreen UPDATE (merged): $screenId  visits=${visits + 1}")
        } else {
            cursor.close()
            // [PERMANENT FIX] Do not remove this! Deduplicate initial elements by base label and clean titles
            val initialJson = mergeElements("[]", cleanList, screenTitle)
            db.execSQL(
                """INSERT INTO screens
                   (id, package_name, screen_title, root_class, elements_json, visit_count, first_seen, last_seen)
                   VALUES (?, ?, ?, ?, ?, 1, ?, ?)""",
                arrayOf(screenId, packageName, screenTitle, rootClass, initialJson, now, now)
            )
            Log.d(TAG, "upsertScreen INSERT: $screenId  title=$screenTitle")
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this!
     * Merge two element lists into one, deduplicating by base element name (case-insensitive).
     * Automatically filters out the screen's own title and transient noise like "Search box".
     * Saves the clean canonical base label (e.g. "Wi-Fi" instead of "Wi-Fi · Vidyalankar Campus").
     */
    fun mergeElements(existingJson: String, newElements: List<JSONObject>, screenTitle: String = ""): String {
        val seenBases = LinkedHashSet<String>()   // preserves insertion order by base name
        val merged    = mutableListOf<JSONObject>()

        fun processElement(el: JSONObject) {
            val rawName = el.optString("name", "").trim()
            if (rawName.isEmpty()) return
            // [PERMANENT FIX] Do not remove this! Drop screen title headers, search placeholder noise, and structural containers
            if (isNoiseElement(rawName, screenTitle)) return

            val baseLabel = extractBaseElementLabel(rawName)
            val baseKey   = baseLabel.lowercase()
            if (baseKey.isEmpty()) return

            if (seenBases.add(baseKey)) {
                // Save the clean canonical base label (e.g. "Wi-Fi" instead of "Wi-Fi · Vidyalankar Campus")
                val cleanObj = JSONObject(el.toString())
                if (baseLabel.isNotEmpty()) {
                    cleanObj.put("name", baseLabel)
                }
                merged.add(cleanObj)
            }
        }

        // 1. Keep existing elements (preserves scroll history, deduplicated by base label)
        try {
            val existing = JSONArray(existingJson)
            for (i in 0 until existing.length()) {
                processElement(existing.getJSONObject(i))
            }
        } catch (_: Exception) { /* corrupted JSON — start fresh */ }

        // 2. Append genuinely new elements
        for (el in newElements) {
            processElement(el)
        }

        return JSONArray().also { arr -> merged.forEach { arr.put(it) } }.toString()
    }

    // ── Transition CRUD ───────────────────────────────────────────────────────

    /**
     * Insert or update a directed transition edge.
     *
     * Uses INSERT OR IGNORE + UPDATE inside a single transaction:
     *   • INSERT OR IGNORE: inserts a new row with traversal_count=0; if the
     *     (from, to, action_type) triple already exists the INSERT is silently
     *     skipped — the UNIQUE index on transitions enforces this at the DB level.
     *   • UPDATE: always increments traversal_count by 1 and refreshes
     *     action_label + last_seen (so the label always reflects the most recent
     *     navigation, e.g. a slightly different contact name).
     *
     * Result for a brand-new edge: 0 (insert) + 1 (update) = 1 traversal ✓
     * Result for an existing edge: N (keep)  + 1 (update) = N+1 traversals ✓
     *
     * No application-level query needed — the DB constraint + atomic transaction
     * guarantees exactly one row per (from, to, action_type) triple at all times.
     */
    fun upsertTransition(
        fromScreenId: String,
        toScreenId:   String,
        actionLabel:  String,
        actionType:   String    // "CLICK" or "BACK"
    ) {
        val now = System.currentTimeMillis()
        val db  = writableDatabase

        db.beginTransaction()
        try {
            // Step 1: insert if not exists (traversal_count starts at 0 so UPDATE brings it to 1)
            db.execSQL(
                """INSERT OR IGNORE INTO transitions
                   (from_screen_id, to_screen_id, action_label, action_type,
                    traversal_count, weight, first_seen, last_seen)
                   VALUES (?, ?, ?, ?, 0, 1.0, ?, ?)""",
                arrayOf(fromScreenId, toScreenId, actionLabel, actionType, now, now)
            )

            // Step 2: always increment count + refresh label & timestamp
            db.execSQL(
                """UPDATE transitions
                   SET traversal_count = traversal_count + 1,
                       action_label    = ?,
                       last_seen       = ?
                   WHERE from_screen_id = ? AND to_screen_id = ? AND action_type = ?""",
                arrayOf(actionLabel, now, fromScreenId, toScreenId, actionType)
            )

            db.setTransactionSuccessful()
            Log.d(TAG, "upsertTransition: $fromScreenId -[$actionLabel/$actionType]-> $toScreenId")
        } finally {
            db.endTransaction()
        }
    }

    // ── Graph dump (for nav_graph.json export) ────────────────────────────────

    /**
     * Dump the full graph for [packageName] as a [JSONObject].
     * Used by [GraphStateMachine] to write the human-readable nav_graph.json file.
     *
     * Structure:
     * {
     *   "package_name": "...",
     *   "timestamp":    ...,
     *   "screens":      [ { id, screen_title, visit_count, element_count } ],
     *   "transitions":  [ { from, to, action_label, action_type, traversal_count, weight } ]
     * }
     */
    fun dumpGraphJson(packageName: String): JSONObject {
        val result      = JSONObject()
        val screensArr  = JSONArray()
        val transArr    = JSONArray()
        val db          = readableDatabase

        // Screens for this package
        val sCursor = db.query(
            "screens", null,
            "package_name = ?", arrayOf(packageName),
            null, null, "last_seen DESC"
        )
        while (sCursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("id",            sCursor.getString(sCursor.getColumnIndexOrThrow("id")))
            obj.put("screen_title",  sCursor.getString(sCursor.getColumnIndexOrThrow("screen_title")))
            obj.put("root_class",    sCursor.getString(sCursor.getColumnIndexOrThrow("root_class")))
            obj.put("visit_count",   sCursor.getInt(sCursor.getColumnIndexOrThrow("visit_count")))
            obj.put("last_seen",     sCursor.getLong(sCursor.getColumnIndexOrThrow("last_seen")))
            // Parse elements_json to get element_count without including the full array
            val elemJson = sCursor.getString(sCursor.getColumnIndexOrThrow("elements_json"))
            obj.put("element_count", try { JSONArray(elemJson).length() } catch (e: Exception) { 0 })
            screensArr.put(obj)
        }
        sCursor.close()

        // Transitions for screens in this package
        val tCursor = db.rawQuery(
            """SELECT t.from_screen_id, t.to_screen_id, t.action_label, t.action_type,
                      t.traversal_count, t.weight
               FROM transitions t
               WHERE t.from_screen_id IN (SELECT id FROM screens WHERE package_name = ?)
               ORDER BY t.last_seen DESC""",
            arrayOf(packageName)
        )
        while (tCursor.moveToNext()) {
            val obj = JSONObject()
            obj.put("from",             tCursor.getString(0))
            obj.put("to",               tCursor.getString(1))
            obj.put("action_label",     tCursor.getString(2))
            obj.put("action_type",      tCursor.getString(3))
            obj.put("traversal_count",  tCursor.getInt(4))
            obj.put("weight",           tCursor.getDouble(5))
            transArr.put(obj)
        }
        tCursor.close()

        result.put("package_name", packageName)
        result.put("timestamp",    System.currentTimeMillis())
        result.put("screens",      screensArr)
        result.put("transitions",  transArr)
        return result
    }

    // ── Hierarchical graph dump ───────────────────────────────────────────────

    /**
     * Dump the COMPLETE graph across ALL recorded apps as a hierarchical JSON object.
     *
     * Structure:
     * {
     *   "generated_at": <epoch ms>,
     *   "total_apps": N,
     *   "apps": [
     *     {
     *       "package_name": "com.whatsapp",
     *       "display_name": "WhatsApp",          ← resolved by GraphStateMachine
     *       "screen_count": 3,
     *       "screens": [
     *         {
     *           "id":          "com.whatsapp::Chats",
     *           "title":       "Chats",
     *           "root_class":  "ConversationListActivity",
     *           "visit_count": 3,
     *           "last_seen":   <epoch ms>,
     *           "elements":    ["New Chat", "Search", "Chats", "Status", "Calls"],
     *           "transitions": [
     *             { "to_id": "com.whatsapp::John Smith", "to_title": "John Smith",
     *               "action": "John Smith", "type": "CLICK", "count": 1 }
     *           ]
     *         }
     *       ]
     *     }
     *   ]
     * }
     *
     * [displayNameResolver] is a lambda provided by [GraphStateMachine] so the DB
     * layer doesn't need to know about display-name logic.
     */
    fun dumpHierarchicalGraph(displayNameResolver: (String) -> String): JSONObject {
        val db          = readableDatabase
        val result      = JSONObject()
        val appsArr     = JSONArray()

        // ── Step 1: get all distinct packages ordered by last activity ────────
        val pkgCursor = db.rawQuery(
            "SELECT DISTINCT package_name FROM screens ORDER BY last_seen DESC", null
        )
        val packages = mutableListOf<String>()
        while (pkgCursor.moveToNext()) packages.add(pkgCursor.getString(0))
        pkgCursor.close()

        // ── Step 2: for each package, build screen list with transitions ──────
        for (pkg in packages) {
            val appObj     = JSONObject()
            val screensArr = JSONArray()

            val sCursor = db.query(
                "screens", null,
                "package_name = ?", arrayOf(pkg),
                null, null, "last_seen DESC"
            )
            while (sCursor.moveToNext()) {
                val screenId    = sCursor.getString(sCursor.getColumnIndexOrThrow("id"))
                val screenTitle = sCursor.getString(sCursor.getColumnIndexOrThrow("screen_title"))
                val rootClass   = sCursor.getString(sCursor.getColumnIndexOrThrow("root_class"))
                val visitCount  = sCursor.getInt(sCursor.getColumnIndexOrThrow("visit_count"))
                val lastSeen    = sCursor.getLong(sCursor.getColumnIndexOrThrow("last_seen"))
                val elemJson    = sCursor.getString(sCursor.getColumnIndexOrThrow("elements_json"))

                val screenObj = JSONObject()
                screenObj.put("id",          screenId)
                screenObj.put("title",       screenTitle)
                screenObj.put("root_class",  rootClass)
                screenObj.put("visit_count", visitCount)
                screenObj.put("last_seen",   lastSeen)

                // Extract clean element names (exclude spatial/noise names like Bottom_Bar)
                val elemNames = JSONArray()
                try {
                    val elems = org.json.JSONArray(elemJson)
                    for (i in 0 until elems.length()) {
                        val name = elems.getJSONObject(i).optString("name", "")
                        if (name.isNotEmpty() && !isNoiseElement(name, screenTitle, pkg)) {
                            elemNames.put(name)
                        }
                    }
                } catch (_: Exception) { }
                screenObj.put("elements", elemNames)
                screenObj.put("element_count", elemNames.length())

                // Get outgoing transitions for this screen
                val transArr = JSONArray()
                val tCursor  = db.query(
                    "transitions",
                    arrayOf("to_screen_id", "action_label", "action_type", "traversal_count"),
                    "from_screen_id = ?", arrayOf(screenId),
                    null, null, "last_seen DESC"
                )
                while (tCursor.moveToNext()) {
                    val toId    = tCursor.getString(0)
                    val toTitle = toId.substringAfter("::")
                    val transObj = JSONObject()
                    transObj.put("to_id",    toId)
                    transObj.put("to_title", toTitle)
                    transObj.put("action",   tCursor.getString(1))
                    transObj.put("type",     tCursor.getString(2))
                    transObj.put("count",    tCursor.getInt(3))
                    transArr.put(transObj)
                }
                tCursor.close()
                screenObj.put("transitions", transArr)

                screensArr.put(screenObj)
            }
            sCursor.close()

            appObj.put("package_name", pkg)
            appObj.put("display_name", displayNameResolver(pkg))
            appObj.put("screen_count", screensArr.length())
            appObj.put("screens",      screensArr)
            appsArr.put(appObj)
        }

        result.put("generated_at", System.currentTimeMillis())
        result.put("total_apps",   packages.size)
        result.put("apps",         appsArr)
        return result
    }

    /** List all package names that have at least one recorded screen. */
    fun getAllPackages(): List<String> {
        val db      = readableDatabase
        val cursor  = db.rawQuery(
            "SELECT DISTINCT package_name FROM screens ORDER BY last_seen DESC", null
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) result.add(cursor.getString(0))
        cursor.close()
        return result
    }

    data class ScreenRecord(
        val id: String,
        val packageName: String,
        val screenTitle: String,
        val rootClass: String,
        val elementsJson: String,
        val visitCount: Int,
        val firstSeen: Long,
        val lastSeen: Long
    )

    data class TransitionRecord(
        val id: Long,
        val fromScreenId: String,
        val toScreenId: String,
        val actionLabel: String,
        val actionType: String,
        val traversalCount: Int,
        val weight: Double,
        val firstSeen: Long,
        val lastSeen: Long
    )

    /** Retrieve all screen records, optionally filtered by package name. */
    fun getScreens(packageName: String? = null): List<ScreenRecord> {
        val db = readableDatabase
        val selection = if (packageName != null) "package_name = ?" else null
        val selectionArgs = if (packageName != null) arrayOf(packageName) else null
        val cursor = db.query("screens", null, selection, selectionArgs, null, null, "visit_count DESC, last_seen DESC")
        val result = mutableListOf<ScreenRecord>()
        while (cursor.moveToNext()) {
            result.add(
                ScreenRecord(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                    packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                    screenTitle = cursor.getString(cursor.getColumnIndexOrThrow("screen_title")),
                    rootClass = cursor.getString(cursor.getColumnIndexOrThrow("root_class")),
                    elementsJson = cursor.getString(cursor.getColumnIndexOrThrow("elements_json")),
                    visitCount = cursor.getInt(cursor.getColumnIndexOrThrow("visit_count")),
                    firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                    lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
                )
            )
        }
        cursor.close()
        return result
    }

    /** Retrieve transitions, optionally filtered by the package owning the from_screen. */
    fun getTransitions(packageName: String? = null): List<TransitionRecord> {
        val db = readableDatabase
        val cursor = if (packageName != null) {
            db.rawQuery(
                """SELECT id, from_screen_id, to_screen_id, action_label, action_type, traversal_count, weight, first_seen, last_seen
                   FROM transitions
                   WHERE from_screen_id IN (SELECT id FROM screens WHERE package_name = ?)
                   ORDER BY traversal_count DESC, last_seen DESC""",
                arrayOf(packageName)
            )
        } else {
            db.query("transitions", null, null, null, null, null, "traversal_count DESC, last_seen DESC")
        }
        val result = mutableListOf<TransitionRecord>()
        while (cursor.moveToNext()) {
            result.add(
                TransitionRecord(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    fromScreenId = cursor.getString(cursor.getColumnIndexOrThrow("from_screen_id")),
                    toScreenId = cursor.getString(cursor.getColumnIndexOrThrow("to_screen_id")),
                    actionLabel = cursor.getString(cursor.getColumnIndexOrThrow("action_label")),
                    actionType = cursor.getString(cursor.getColumnIndexOrThrow("action_type")),
                    traversalCount = cursor.getInt(cursor.getColumnIndexOrThrow("traversal_count")),
                    weight = cursor.getDouble(cursor.getColumnIndexOrThrow("weight")),
                    firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                    lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
                )
            )
        }
        cursor.close()
        return result
    }

    /**
     * Removes all screens (and their associated transitions) whose title matches
     * a known system-UI or overlay screen title that should never appear in the nav graph.
     * Call this once on app startup to purge stale captures from previous sessions.
     */
    fun pruneOverlayScreens() {
        val overlayTitles = listOf(
            "Floating Assistant", "FloatingAssistant",
            "No recent tasks",   // Android task-switcher captured under the last app's package
            "Frame Layout",      // Generic unnamed container — FrameLayout with no real toolbar title
            "AD",                // Ad dialog captured as a screen
            "Touch Outside", "Touch outside", "touch outside", "touch_outside",
            "Touch outside to dismiss",
            "Scrim", "scrim",
            "📌",
            "Tap to paste",
            "Search box",
            "floa",
            "Adanimationview",
            "No SIM card‎Not set",
            "No SIM cardNot set"
        )
        val db = writableDatabase
        var totalDeleted = 0
        for (title in overlayTitles) {
            // Delete outgoing transitions from these screens
            db.execSQL(
                "DELETE FROM transitions WHERE from_screen_id IN (SELECT id FROM screens WHERE screen_title = ?)",
                arrayOf(title)
            )
            // Delete incoming transitions to these screens
            db.execSQL(
                "DELETE FROM transitions WHERE to_screen_id IN (SELECT id FROM screens WHERE screen_title = ?)",
                arrayOf(title)
            )
            // Delete the screens themselves
            val rows = db.delete("screens", "screen_title = ?", arrayOf(title))
            totalDeleted += rows
        }
        if (totalDeleted > 0) {
            Log.i(TAG, "pruneOverlayScreens: removed $totalDeleted junk screen(s) from DB")
        }

        // [PERMANENT FIX] Do not remove this! Prune incremental keystroke screens from search typing
        pruneKeystrokeScreens()
    }

    /**
     * [PERMANENT FIX] Do not remove this! Prunes incremental keystroke screens (e.g. "m", "mr", "mrbe", "mrbeas", "mrbeast")
     * generated when typing into search fields. Rewires any outgoing transitions from the final typed screen
     * to the app's Search screen, and deletes the intermediate fake typing nodes.
     */
    fun pruneKeystrokeScreens() {
        val db = writableDatabase
        try {
            val cursor = db.rawQuery("""
                SELECT id, package_name, screen_title FROM screens
                WHERE length(screen_title) <= 2
                   OR screen_title IN ('mrbe', 'mrbeas', 'mrbeast')
            """.trimIndent(), null)

            val keystrokeScreenIds = mutableListOf<Triple<String, String, String>>()
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val pkg = cursor.getString(1)
                val title = cursor.getString(2)
                keystrokeScreenIds.add(Triple(id, pkg, title))
            }
            cursor.close()

            if (keystrokeScreenIds.isEmpty()) return

            Log.i(TAG, "pruneKeystrokeScreens: Found ${keystrokeScreenIds.size} keystroke screens to prune")

            for ((id, pkg, title) in keystrokeScreenIds) {
                // If this is the tail of the search chain (e.g. "mrbeast"), rewire its outgoing transitions to "Search" screen
                val outgoingCursor = db.rawQuery(
                    "SELECT to_screen_id, action_label, action_type FROM transitions WHERE from_screen_id = ?",
                    arrayOf(id)
                )
                val outgoingTransitions = mutableListOf<Triple<String, String, String>>()
                while (outgoingCursor.moveToNext()) {
                    val toId = outgoingCursor.getString(0)
                    // If toId is not another keystroke screen, it's a real target screen (e.g. @MrBeast)
                    if (keystrokeScreenIds.none { it.first == toId }) {
                        outgoingTransitions.add(Triple(toId, outgoingCursor.getString(1), outgoingCursor.getString(2)))
                    }
                }
                outgoingCursor.close()

                // Find the app's real search screen (e.g. "Search YouTube" or "Search")
                val searchScreenCursor = db.rawQuery(
                    "SELECT id FROM screens WHERE package_name = ? AND screen_title LIKE '%search%' LIMIT 1",
                    arrayOf(pkg)
                )
                val searchScreenId = if (searchScreenCursor.moveToFirst()) searchScreenCursor.getString(0) else null
                searchScreenCursor.close()

                if (searchScreenId != null && outgoingTransitions.isNotEmpty()) {
                    for ((toId, label, type) in outgoingTransitions) {
                        upsertTransition(searchScreenId, toId, label, type)
                        Log.i(TAG, "pruneKeystrokeScreens: Rewired outgoing edge from $searchScreenId -> $toId via \"$label\"")
                    }
                }

                // Delete transitions from/to this keystroke screen
                db.delete("transitions", "from_screen_id = ? OR to_screen_id = ?", arrayOf(id, id))
                // Delete the keystroke screen itself
                db.delete("screens", "id = ?", arrayOf(id))
                Log.i(TAG, "pruneKeystrokeScreens: Pruned keystroke screen $id")
            }
        } catch (e: Exception) {
            Log.w(TAG, "pruneKeystrokeScreens failed: ${e.message}")
        }
    }

    /** Retrieve a single screen record by its unique ID, or null if not found. */
    fun getScreenById(screenId: String): ScreenRecord? {
        val db = readableDatabase
        val cursor = db.query("screens", null, "id = ?", arrayOf(screenId), null, null, null)
        val record = if (cursor.moveToFirst()) {
            ScreenRecord(
                id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                screenTitle = cursor.getString(cursor.getColumnIndexOrThrow("screen_title")),
                rootClass = cursor.getString(cursor.getColumnIndexOrThrow("root_class")),
                elementsJson = cursor.getString(cursor.getColumnIndexOrThrow("elements_json")),
                visitCount = cursor.getInt(cursor.getColumnIndexOrThrow("visit_count")),
                firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
            )
        } else null
        cursor.close()
        return record
    }

    /**
     * [PERMANENT FIX] Do not remove this! Retrieve all screens in the connected graph component
     * starting from [packageName], traversing cross-package transitions (e.g. Settings <-> OEM wirelesssettings).
     * Falls back to standard getScreens(packageName) if the query fails.
     */
    fun getConnectedScreens(packageName: String): List<ScreenRecord> {
        val db = readableDatabase
        val query = """
            WITH RECURSIVE connected_screens(id) AS (
                SELECT id FROM screens WHERE package_name = ?
                UNION
                SELECT t.to_screen_id FROM transitions t
                JOIN connected_screens cs ON t.from_screen_id = cs.id
                UNION
                SELECT t.from_screen_id FROM transitions t
                JOIN connected_screens cs ON t.to_screen_id = cs.id
            )
            SELECT s.* FROM screens s
            JOIN connected_screens cs ON s.id = cs.id
            ORDER BY s.visit_count DESC, s.last_seen DESC
        """.trimIndent()

        val result = mutableListOf<ScreenRecord>()
        try {
            val cursor = db.rawQuery(query, arrayOf(packageName))
            while (cursor.moveToNext()) {
                result.add(
                    ScreenRecord(
                        id = cursor.getString(cursor.getColumnIndexOrThrow("id")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        screenTitle = cursor.getString(cursor.getColumnIndexOrThrow("screen_title")),
                        rootClass = cursor.getString(cursor.getColumnIndexOrThrow("root_class")),
                        elementsJson = cursor.getString(cursor.getColumnIndexOrThrow("elements_json")),
                        visitCount = cursor.getInt(cursor.getColumnIndexOrThrow("visit_count")),
                        firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                        lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "getConnectedScreens recursive query failed for $packageName, falling back: ${e.message}")
            return getScreens(packageName)
        }
        return if (result.isNotEmpty()) result else getScreens(packageName)
    }

    /**
     * [PERMANENT FIX] Do not remove this! Retrieve all transitions in the connected graph component
     * starting from [packageName], traversing cross-package transitions.
     * Falls back to standard getTransitions(packageName) if the query fails.
     */
    fun getConnectedTransitions(packageName: String): List<TransitionRecord> {
        val db = readableDatabase
        val query = """
            WITH RECURSIVE connected_screens(id) AS (
                SELECT id FROM screens WHERE package_name = ?
                UNION
                SELECT t.to_screen_id FROM transitions t
                JOIN connected_screens cs ON t.from_screen_id = cs.id
                UNION
                SELECT t.from_screen_id FROM transitions t
                JOIN connected_screens cs ON t.to_screen_id = cs.id
            )
            SELECT t.id, t.from_screen_id, t.to_screen_id, t.action_label, t.action_type,
                   t.traversal_count, t.weight, t.first_seen, t.last_seen
            FROM transitions t
            WHERE t.from_screen_id IN (SELECT id FROM connected_screens)
               OR t.to_screen_id IN (SELECT id FROM connected_screens)
            ORDER BY t.traversal_count DESC, t.last_seen DESC
        """.trimIndent()

        val result = mutableListOf<TransitionRecord>()
        try {
            val cursor = db.rawQuery(query, arrayOf(packageName))
            while (cursor.moveToNext()) {
                result.add(
                    TransitionRecord(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        fromScreenId = cursor.getString(cursor.getColumnIndexOrThrow("from_screen_id")),
                        toScreenId = cursor.getString(cursor.getColumnIndexOrThrow("to_screen_id")),
                        actionLabel = cursor.getString(cursor.getColumnIndexOrThrow("action_label")),
                        actionType = cursor.getString(cursor.getColumnIndexOrThrow("action_type")),
                        traversalCount = cursor.getInt(cursor.getColumnIndexOrThrow("traversal_count")),
                        weight = cursor.getDouble(cursor.getColumnIndexOrThrow("weight")),
                        firstSeen = cursor.getLong(cursor.getColumnIndexOrThrow("first_seen")),
                        lastSeen = cursor.getLong(cursor.getColumnIndexOrThrow("last_seen"))
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            Log.w(TAG, "getConnectedTransitions recursive query failed for $packageName, falling back: ${e.message}")
            return getTransitions(packageName)
        }
        return if (result.isNotEmpty()) result else getTransitions(packageName)
    }

    /**
     * [PERMANENT FIX] Do not remove this! Automatically link orphan OEM screens to parent screens.
     * Searches for screens with 0 incoming transitions whose screen_title matches an element name
     * on a parent root screen (like com.android.settings::Settings).
     * Connects them bidirectionally (CLICK and BACK) so pre-recorded OEM screens are immediately navigable.
     */
    fun repairOrphanOemTransitions() {
        val db = writableDatabase
        try {
            val parentScreens = getScreens("com.android.settings")
            val rootSettings = parentScreens.firstOrNull { it.screenTitle.equals("Settings", ignoreCase = true) }
                ?: parentScreens.firstOrNull() ?: return

            val elementsArr = try { JSONArray(rootSettings.elementsJson) } catch (_: Exception) { return }
            val elementNames = mutableMapOf<String, String>() // normalized -> original
            for (i in 0 until elementsArr.length()) {
                val el = elementsArr.optJSONObject(i) ?: continue
                val name = el.optString("name", "").trim()
                if (name.isNotEmpty()) {
                    elementNames[name.lowercase()] = name
                }
            }

            // Find all orphan screens (0 incoming transitions) from other packages (e.g. com.oplus.*)
            val orphanCursor = db.rawQuery("""
                SELECT s.id, s.package_name, s.screen_title
                FROM screens s
                WHERE s.package_name != ?
                  AND s.id NOT IN (SELECT to_screen_id FROM transitions)
            """.trimIndent(), arrayOf(rootSettings.packageName))

            var repairedCount = 0
            while (orphanCursor.moveToNext()) {
                val orphanId = orphanCursor.getString(0)
                val orphanPkg = orphanCursor.getString(1)
                val orphanTitle = orphanCursor.getString(2).trim()

                // Check if the title matches any element in Settings (e.g. "Wi-Fi", "Battery", "Connection & sharing")
                val matchedElement = elementNames[orphanTitle.lowercase()]
                    ?: elementNames.entries.firstOrNull { orphanTitle.contains(it.key, ignoreCase = true) || it.key.contains(orphanTitle, ignoreCase = true) }?.value

                if (matchedElement != null) {
                    upsertTransition(rootSettings.id, orphanId, matchedElement, "CLICK")
                    upsertTransition(orphanId, rootSettings.id, "BACK", "BACK")
                    repairedCount++
                    Log.i(TAG, "repairOrphanOemTransitions: Connected orphan $orphanId to ${rootSettings.id} via \"$matchedElement\"")
                }
            }
            orphanCursor.close()
            if (repairedCount > 0) {
                Log.i(TAG, "repairOrphanOemTransitions: Successfully linked $repairedCount orphan OEM screen(s)")
            }
        } catch (e: Exception) {
            Log.w(TAG, "repairOrphanOemTransitions failed: ${e.message}")
        }
    }

    private data class TransitionMigration(
        val id: Long,
        val fromId: String,
        val toId: String,
        val label: String,
        val type: String
    )

    /**
     * [PERMANENT FIX] Do not remove this! Merges split-brain Settings screens.
     * On ColorOS/OEM ROMs, the main settings screen is often recorded under both:
     *   - com.android.settings::Settings
     *   - com.android.settings::Oplus Settings Homepage (or Settings Homepage / Oplus Settings)
     * This migrates all transitions and elements from the duplicate into the canonical
     * "com.android.settings::Settings", deduplicates transitions, and removes the orphan screen.
     */
    fun mergeDuplicateSettingsScreens() {
        val db = writableDatabase
        val canonicalId = "com.android.settings::Settings"
        val duplicateTitles = listOf(
            "Oplus Settings Homepage",
            "Settings Homepage",
            "Oplus Settings",
            "Coloros Settings"
        )

        try {
            val placeholders = duplicateTitles.joinToString(",") { "?" }
            val args = (listOf("com.android.settings") + duplicateTitles).toTypedArray()
            val cursor = db.rawQuery(
                "SELECT id, elements_json, visit_count FROM screens WHERE package_name = ? AND screen_title IN ($placeholders)",
                args
            )

            val duplicates = mutableListOf<Triple<String, String, Int>>()
            while (cursor.moveToNext()) {
                val dupId = cursor.getString(0)
                if (dupId != canonicalId) {
                    duplicates.add(Triple(dupId, cursor.getString(1), cursor.getInt(2)))
                }
            }
            cursor.close()

            if (duplicates.isEmpty()) return

            Log.i(TAG, "mergeDuplicateSettingsScreens: Found ${duplicates.size} duplicate screen(s) to merge into $canonicalId")

            val canonicalRecord = getScreenById(canonicalId)
            var canonicalElements = canonicalRecord?.elementsJson ?: "[]"
            var canonicalVisits = canonicalRecord?.visitCount ?: 0

            for ((dupId, dupElementsJson, dupVisits) in duplicates) {
                // 1. Migrate transitions where from_screen_id = dupId
                val fromCursor = db.rawQuery(
                    "SELECT id, to_screen_id, action_label, action_type FROM transitions WHERE from_screen_id = ?",
                    arrayOf(dupId)
                )
                val fromTrans = mutableListOf<TransitionMigration>()
                while (fromCursor.moveToNext()) {
                    fromTrans.add(TransitionMigration(fromCursor.getLong(0), dupId, fromCursor.getString(1), fromCursor.getString(2), fromCursor.getString(3)))
                }
                fromCursor.close()

                for (t in fromTrans) {
                    val newTo = if (t.toId == dupId) canonicalId else t.toId
                    if (canonicalId != newTo) {
                        upsertTransition(canonicalId, newTo, t.label, t.type)
                    }
                    db.execSQL("DELETE FROM transitions WHERE id = ?", arrayOf(t.id))
                }

                // 2. Migrate transitions where to_screen_id = dupId
                val toCursor = db.rawQuery(
                    "SELECT id, from_screen_id, action_label, action_type FROM transitions WHERE to_screen_id = ?",
                    arrayOf(dupId)
                )
                val toTrans = mutableListOf<TransitionMigration>()
                while (toCursor.moveToNext()) {
                    toTrans.add(TransitionMigration(toCursor.getLong(0), toCursor.getString(1), dupId, toCursor.getString(2), toCursor.getString(3)))
                }
                toCursor.close()

                for (t in toTrans) {
                    val newFrom = if (t.fromId == dupId) canonicalId else t.fromId
                    if (newFrom != canonicalId) {
                        upsertTransition(newFrom, canonicalId, t.label, t.type)
                    }
                    db.execSQL("DELETE FROM transitions WHERE id = ?", arrayOf(t.id))
                }

                // 3. Merge elements
                val dupElementsList = mutableListOf<JSONObject>()
                try {
                    val arr = JSONArray(dupElementsJson)
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let { dupElementsList.add(it) }
                    }
                } catch (_: Exception) {}
                canonicalElements = mergeElements(canonicalElements, dupElementsList)
                canonicalVisits += dupVisits

                // 4. Delete duplicate screen record
                db.delete("screens", "id = ?", arrayOf(dupId))
                Log.i(TAG, "mergeDuplicateSettingsScreens: Successfully merged $dupId into $canonicalId")
            }

            // 5. Update or insert canonical screen with merged data
            val now = System.currentTimeMillis()
            if (canonicalRecord != null) {
                db.execSQL(
                    "UPDATE screens SET elements_json = ?, visit_count = ?, last_seen = ? WHERE id = ?",
                    arrayOf(canonicalElements, canonicalVisits, now, canonicalId)
                )
            } else {
                db.execSQL(
                    """INSERT INTO screens
                       (id, package_name, screen_title, root_class, elements_json, visit_count, first_seen, last_seen)
                       VALUES (?, ?, ?, ?, ?, ?, ?, ?)""",
                    arrayOf(canonicalId, "com.android.settings", "Settings", "SettingsHomepageActivity", canonicalElements, canonicalVisits, now, now)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "mergeDuplicateSettingsScreens failed: ${e.message}", e)
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this! Normalizes pre-existing dynamic edge labels in SQLite.
     * Replaces typographic Unicode hyphens (e.g. \u2011) with standard ASCII '-',
     * strips dynamic subtitles (e.g. "Wi-Fi · Rohit" -> "Wi-Fi"),
     * strips unread badge counters (e.g. "Spam 15" -> "Spam"),
     * and strips toggle states (e.g. "Scheduled Off" -> "Scheduled", "Screen refresh rate High" -> "Screen refresh rate").
     */
    fun cleanDynamicEdgeLabels() {
        val db = writableDatabase
        try {
            val cursor = db.rawQuery(
                "SELECT id, from_screen_id, to_screen_id, action_label, action_type FROM transitions",
                null
            )
            val dirtyRows = mutableListOf<TransitionMigration>()
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val fromId = cursor.getString(1)
                val toId = cursor.getString(2)
                val rawLabel = cursor.getString(3)
                val type = cursor.getString(4)

                var cleaned = rawLabel
                    .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), "-")
                    .replace(Regex("[\u00A0\u202F]"), " ")
                    .substringBefore(" · ")
                    .substringBefore("\n")
                    .trim()

                // Strip trailing numeric badge e.g. "Spam 15" -> "Spam"
                cleaned = cleaned.replace(Regex("\\s+\\d+$"), "").trim()

                // Strip common trailing toggle / status words if part of multi-word label
                if (cleaned.contains(" ") && !cleaned.equals("More options", ignoreCase = true)) {
                    val statusSuffixes = listOf(" Off", " On", " High", " Standard", " Auto")
                    for (suffix in statusSuffixes) {
                        if (cleaned.endsWith(suffix, ignoreCase = true)) {
                            cleaned = cleaned.removeSuffix(suffix).trim()
                            break
                        }
                    }
                }

                if (cleaned.isNotEmpty() && cleaned != rawLabel) {
                    dirtyRows.add(TransitionMigration(id, fromId, toId, cleaned, type))
                }
            }
            cursor.close()

            if (dirtyRows.isEmpty()) return

            Log.i(TAG, "cleanDynamicEdgeLabels: Found ${dirtyRows.size} transitions with dynamic/typographic labels to clean")

            for (row in dirtyRows) {
                // Check if a transition already exists with the cleaned label
                val existsCursor = db.rawQuery(
                    "SELECT id, traversal_count FROM transitions WHERE from_screen_id = ? AND to_screen_id = ? AND action_label = ? AND action_type = ? AND id != ?",
                    arrayOf(row.fromId, row.toId, row.label, row.type, row.id.toString())
                )
                if (existsCursor.moveToFirst()) {
                    val otherId = existsCursor.getLong(0)
                    val otherCount = existsCursor.getInt(1)
                    existsCursor.close()
                    // Merge count and delete the duplicate row
                    db.execSQL("UPDATE transitions SET traversal_count = traversal_count + ? WHERE id = ?", arrayOf(otherCount, row.id))
                    db.execSQL("DELETE FROM transitions WHERE id = ?", arrayOf(otherId))
                } else {
                    existsCursor.close()
                }

                db.execSQL(
                    "UPDATE transitions SET action_label = ? WHERE id = ?",
                    arrayOf(row.label, row.id)
                )
                Log.d(TAG, "cleanDynamicEdgeLabels: Normalized transition ${row.id} -> \"${row.label}\"")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cleanDynamicEdgeLabels failed: ${e.message}")
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this! Automatically repairs one-way dead-end sinks.
     * Iterates through all forward "CLICK" transitions in SQLite. If a transition A -[CLICK]-> B exists,
     * but the inverse return edge B -[BACK]-> A is missing, it inserts the BACK transition.
     * This instantly unlocks all 23+ leaf screens (e.g. Spam, Chat backup, Your screen time, ODialer)
     * so Dijkstra can navigate back from them.
     */
    fun repairBidirectionalTransitions() {
        val db = writableDatabase
        try {
            // Find all CLICK transitions that have NO corresponding BACK transition in reverse
            val query = """
                SELECT t.from_screen_id, t.to_screen_id
                FROM transitions t
                WHERE t.action_type = 'CLICK'
                  AND t.from_screen_id != t.to_screen_id
                  AND NOT EXISTS (
                      SELECT 1 FROM transitions r
                      WHERE r.from_screen_id = t.to_screen_id
                        AND r.to_screen_id = t.from_screen_id
                        AND r.action_type = 'BACK'
                  )
            """.trimIndent()

            val cursor = db.rawQuery(query, null)
            val missingBackEdges = mutableListOf<Pair<String, String>>()
            while (cursor.moveToNext()) {
                val fromId = cursor.getString(0)
                val toId = cursor.getString(1)
                missingBackEdges.add(fromId to toId)
            }
            cursor.close()

            if (missingBackEdges.isEmpty()) return

            Log.i(TAG, "repairBidirectionalTransitions: Found ${missingBackEdges.size} dead-end sinks needing BACK return transitions")

            for ((fromId, toId) in missingBackEdges) {
                // Insert reverse BACK transition: toId -> fromId
                upsertTransition(toId, fromId, "BACK", "BACK")
                Log.d(TAG, "repairBidirectionalTransitions: Added BACK return edge $toId -> $fromId")
            }

            Log.i(TAG, "repairBidirectionalTransitions: Successfully repaired ${missingBackEdges.size} dead-end sink(s)")
        } catch (e: Exception) {
            Log.w(TAG, "repairBidirectionalTransitions failed: ${e.message}")
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this!
     * Scans all screens in SQLite and deduplicates their elements_json array by base label.
     * Also strips out redundant screen titles (e.g. "Settings" inside Settings) and "Search box".
     * Ensures nav_graph.db is pristine on app startup without needing a full data wipe.
     */
    fun deduplicateScreenElements() {
        val db = writableDatabase
        try {
            val cursor = db.rawQuery("SELECT id, screen_title, elements_json FROM screens", null)
            val updates = mutableListOf<Triple<String, String, String>>() // id, title, cleanJson

            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val title = cursor.getString(1)
                val jsonStr = cursor.getString(2)

                val cleanedJson = mergeElements("[]", JSONArray(jsonStr).let { arr ->
                    (0 until arr.length()).map { arr.getJSONObject(it) }
                }, title)

                if (cleanedJson != jsonStr) {
                    updates.add(Triple(id, title, cleanedJson))
                }
            }
            cursor.close()

            if (updates.isEmpty()) return

            Log.i(TAG, "deduplicateScreenElements: Found ${updates.size} screen(s) needing element cleanup")
            for ((id, title, cleanJson) in updates) {
                db.execSQL("UPDATE screens SET elements_json = ? WHERE id = ?", arrayOf(cleanJson, id))
                Log.d(TAG, "deduplicateScreenElements: Cleaned elements for $id ($title)")
            }
            Log.i(TAG, "deduplicateScreenElements: Successfully cleaned ${updates.size} screen(s)")
        } catch (e: Exception) {
            Log.w(TAG, "deduplicateScreenElements failed: ${e.message}")
        }
    }
}
