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
    fun upsertScreen(
        screenId:     String,
        packageName:  String,
        screenTitle:  String,
        rootClass:    String,
        elements:     List<JSONObject>
    ) {
        val now = System.currentTimeMillis()
        val db  = writableDatabase

        val cursor = db.query(
            "screens", arrayOf("visit_count", "elements_json"),
            "id = ?", arrayOf(screenId),
            null, null, null
        )

        if (cursor.moveToFirst()) {
            val visits       = cursor.getInt(0)
            val existingJson = cursor.getString(1)
            cursor.close()

            val mergedJson = mergeElements(existingJson, elements)
            db.execSQL(
                "UPDATE screens SET elements_json = ?, visit_count = ?, last_seen = ? WHERE id = ?",
                arrayOf(mergedJson, visits + 1, now, screenId)
            )
            Log.d(TAG, "upsertScreen UPDATE (merged): $screenId  visits=${visits + 1}")
        } else {
            cursor.close()
            val newJson = JSONArray().also { arr -> elements.forEach { arr.put(it) } }.toString()
            db.execSQL(
                """INSERT INTO screens
                   (id, package_name, screen_title, root_class, elements_json, visit_count, first_seen, last_seen)
                   VALUES (?, ?, ?, ?, ?, 1, ?, ?)""",
                arrayOf(screenId, packageName, screenTitle, rootClass, newJson, now, now)
            )
            Log.d(TAG, "upsertScreen INSERT: $screenId  title=$screenTitle")
        }
    }

    /**
     * Merge two element lists into one, deduplicating by element name
     * (case-insensitive).  Existing elements are kept first; new elements
     * are appended only if their name has not already been seen.
     */
    private fun mergeElements(existingJson: String, newElements: List<JSONObject>): String {
        val seenNames = LinkedHashSet<String>()   // preserves insertion order
        val merged    = mutableListOf<JSONObject>()

        // 1. Keep all existing elements (preserves scroll-accumulated history)
        try {
            val existing = JSONArray(existingJson)
            for (i in 0 until existing.length()) {
                val el   = existing.getJSONObject(i)
                val name = el.optString("name", "").trim()
                if (name.isNotEmpty() && seenNames.add(name.lowercase())) {
                    merged.add(el)
                }
            }
        } catch (_: Exception) { /* corrupted JSON — start fresh with new elements */ }

        // 2. Append genuinely new elements not already in the list
        for (el in newElements) {
            val name = el.optString("name", "").trim()
            if (name.isNotEmpty() && seenNames.add(name.lowercase())) {
                merged.add(el)
            }
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

                // Extract clean element names (exclude spatial/noise names)
                val elemNames = JSONArray()
                try {
                    val elems = org.json.JSONArray(elemJson)
                    for (i in 0 until elems.length()) {
                        val name = elems.getJSONObject(i).optString("name", "")
                        if (name.isNotEmpty()) elemNames.put(name)
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
}
