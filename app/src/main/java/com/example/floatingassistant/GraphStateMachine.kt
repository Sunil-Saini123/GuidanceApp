package com.example.floatingassistant

import android.content.Context
import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * GraphStateMachine — Phase 3 + 4 (Universal OEM-Agnostic Pipeline)
 *
 * Maintains an in-memory per-package navigation stack and maps screen transitions
 * into a directed graph stored in [NavGraphDatabase].  Exports a hierarchical
 * human-readable snapshot covering ALL apps to nav_graph.json after every update.
 *
 * ── Screen Identity ────────────────────────────────────────────────────────────
 *
 *   screenId = "$packageName::$screenTitle"
 *
 * screenTitle = the topmost, static, non-spatial element in the top 20% of the
 * screen.  "Static" means it passes the dynamic-data filter (no phone numbers,
 * dates, times, or long dynamic strings).  Fallback: Activity class name.
 *
 * ── Navigation Stack Machine ──────────────────────────────────────────────────
 *
 * Each package maintains its own ArrayDeque<String> (screen IDs).
 *
 *   Same screen as top of stack?        → content refresh, upsert elements only
 *   New screen NOT in stack?            → FORWARD: push, draw CLICK edge
 *   Screen found below current in stack → BACK: draw BACK edge, pop above
 *   SCROLL / CONTENT_CHANGED           → upsert elements only, no stack change
 *
 * ── Edge label filtering ──────────────────────────────────────────────────────
 *
 * Before using a clicked element name as an edge label, it is checked against
 * the dynamic-data filter.  Dynamic labels (phone numbers, contact names that
 * are pure sequences, timestamps) are replaced with the destination screen's
 * title so the graph maps NAVIGATION STRUCTURE, not user-specific content.
 *
 * ── Graph JSON structure (nav_graph.json) ────────────────────────────────────
 *
 * Hierarchical across ALL apps:
 * {
 *   "generated_at": <epoch ms>,
 *   "total_apps": 3,
 *   "active_stacks": { "com.whatsapp": ["com.whatsapp::Chats", ...], ... },
 *   "apps": [
 *     {
 *       "package_name": "com.whatsapp",
 *       "display_name": "WhatsApp",
 *       "screen_count": 3,
 *       "screens": [
 *         {
 *           "id":          "com.whatsapp::Chats",
 *           "title":       "Chats",
 *           "visit_count": 3,
 *           "elements":    ["New Chat", "Search", "Chats", "Status", "Calls"],
 *           "transitions": [
 *             { "to_title": "Chat Room", "action": "John Smith", "type": "CLICK", "count": 1 }
 *           ]
 *         }
 *       ]
 *     },
 *     { "package_name": "com.android.settings", "display_name": "Settings", ... }
 *   ]
 * }
 *
 * Pull: adb pull /sdcard/Android/data/com.example.floatingassistant/files/nav_graph.json
 * Pull DB: adb pull /sdcard/Android/data/com.example.floatingassistant/files/nav_graph.db
 */
object GraphStateMachine {

    private const val TAG = "GraphStateMachine"
    const val NAV_GRAPH_FILE_NAME = "nav_graph.json"

    // Spatial label names — never valid screen titles
    private val SPATIAL_LABELS = setOf(
        "Back_Button", "Menu_Button", "Search_Bar", "Action_Bar",
        "Bottom_Nav_Item", "Bottom_Bar", "Scrollable_List"
    )

    // ── Dynamic data filters ──────────────────────────────────────────────────
    // Used to reject phone numbers, timestamps, etc. from becoming screen titles
    // or edge labels — keeps the graph clean and structural.

    private val REGEX_PHONE         = Regex("""\+?\d[\d\s\-(). ]{6,}\d""")
    private val REGEX_DATE_NUMERIC  = Regex("""\b\d{1,2}[/\-]\d{1,2}([/\-]\d{2,4})?\b""")
    private val REGEX_DATE_ALPHA    = Regex(
        """(?i)\b\d{1,2}\s*(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*(\s+\d{2,4})?\b"""
    )
    private val REGEX_TIME          = Regex("""\b\d{1,2}:\d{2}(\s*[APap][Mm])?\b""")
    private val REGEX_RELATIVE_TIME = Regex(
        """(?i)\b\d+\s*(sec(ond)?|min(ute)?|hour|day|week|month|year)s?\s*(ago)?\b"""
    )
    private val REGEX_PURELY_NUMERIC = Regex("""^\d+([.,]\d+)?[KMBkmb%]?$""")

    // Known app package → display name
    private val KNOWN_APP_NAMES = mapOf(
        "com.whatsapp"                        to "WhatsApp",
        "com.whatsapp.w4b"                    to "WhatsApp Business",
        "com.android.settings"                to "Settings",
        "com.google.android.youtube"          to "YouTube",
        "com.google.android.gm"               to "Gmail",
        "com.google.android.apps.messaging"   to "Messages",
        "com.android.messaging"               to "Messages",
        "com.google.android.dialer"           to "Phone",
        "com.android.contacts"                to "Contacts",
        "com.facebook.katana"                 to "Facebook",
        "com.instagram.android"               to "Instagram",
        "com.twitter.android"                 to "X (Twitter)",
        "com.snapchat.android"                to "Snapchat",
        "org.telegram.messenger"              to "Telegram",
        "com.spotify.music"                   to "Spotify",
        "com.netflix.mediaclient"             to "Netflix",
        "com.amazon.mShop.android.shopping"   to "Amazon",
        "com.google.android.apps.maps"        to "Maps",
        "com.google.android.apps.photos"      to "Photos",
        "com.google.android.apps.translate"   to "Translate",
        "com.zhiliaoapp.musically"            to "TikTok",
        "com.ss.android.ugc.trill"            to "TikTok",
        "com.microsoft.teams"                 to "Teams",
        "com.linkedin.android"                to "LinkedIn",
        "com.microsoft.launcher"              to "Microsoft Launcher",
        "com.vivo.launcher"                   to "Vivo Launcher",
        "com.bbk.launcher2"                   to "iQOO / Vivo Home",
        "com.sec.android.app.launcher"        to "Samsung Home",
        "com.miui.home"                       to "MIUI Home",
        "com.oppo.launcher"                   to "OPPO Home",
        "com.oneplus.launcher"                to "OnePlus Home",
    )

    // ── Singleton state ───────────────────────────────────────────────────────

    private lateinit var db:           NavGraphDatabase
    private lateinit var navGraphFile: File

    /** Per-package navigation stacks.  Keyed by packageName. */
    private val navigationStacks = HashMap<String, ArrayDeque<String>>()

    /** Per-package last-clicked label. Cleared after each navigation event. */
    private val lastClickedLabels = HashMap<String, String>()

    // ── Initialisation ────────────────────────────────────────────────────────

    fun init(context: Context, navGraphFile: File) {
        db                = NavGraphDatabase.getInstance(context)
        this.navGraphFile = navGraphFile
        Log.i(TAG, "Initialised — DB: nav_graph.db  JSON: ${navGraphFile.name}")
    }

    // ── Public event API ──────────────────────────────────────────────────────

    /** Called from TYPE_VIEW_CLICKED on the main thread. */
    fun setLastClickedLabel(packageName: String, label: String) {
        lastClickedLabels[packageName] = label
        Log.v(TAG, "Click recorded: $packageName → \"$label\"")
    }

    /**
     * Main entry point — called on [Dispatchers.IO] from the service after Phase 2.
     *
     * @param cleanElements  Phase 2 clean elements for this event.
     * @param packageName    Package owning the active window.
     * @param rootClass      Activity class name (from event.className).
     * @param eventType      "NAVIGATION", "SCROLL", or "CONTENT_CHANGED".
     */
    fun onEvent(
        cleanElements: List<JSONObject>,
        packageName:   String,
        rootClass:     String,
        eventType:     String
    ) {
        val screenTitle = resolveScreenTitle(cleanElements, rootClass)
        val screenId    = "$packageName::$screenTitle"

        when (eventType) {
            "SCROLL", "CONTENT_CHANGED" -> {
                // Scroll: same screen, just update accumulated elements.
                // No stack change, no new transitions.
                db.upsertScreen(screenId, packageName, screenTitle, rootClass, cleanElements)
                Log.d(TAG, "[$eventType] Elements updated for $screenId (${cleanElements.size} elements)")
            }
            else -> {  // NAVIGATION
                handleNavigation(screenId, packageName, screenTitle, rootClass, cleanElements)
            }
        }

        // Rewrite the full hierarchical graph snapshot after every event
        writeNavGraphSnapshot()
    }

    // ── Navigation logic ──────────────────────────────────────────────────────

    private fun handleNavigation(
        screenId:      String,
        packageName:   String,
        screenTitle:   String,
        rootClass:     String,
        cleanElements: List<JSONObject>
    ) {
        val stack            = navigationStacks.getOrPut(packageName) { ArrayDeque() }
        val previousScreenId = stack.lastOrNull()

        // ── Content refresh on the same screen ────────────────────────────────
        if (screenId == previousScreenId) {
            db.upsertScreen(screenId, packageName, screenTitle, rootClass, cleanElements)
            Log.d(TAG, "[NAVIGATION] Content refresh: $screenId")
            return
        }

        // ── BACK vs FORWARD ────────────────────────────────────────────────────
        val stackIndex = stack.indexOfLast { it == screenId }

        if (stackIndex >= 0) {
            // ── BACK: target screen already in stack below current top ────────
            val fromId = stack.last()
            db.upsertTransition(fromId, screenId, "BACK", "BACK")
            Log.i(TAG, "[BACK] $fromId → $screenId  (popped ${stack.size - 1 - stackIndex} screen(s))")
            // Pop everything above the target
            while (stack.size > stackIndex + 1) stack.removeLast()

        } else {
            // ── FORWARD: new screen ────────────────────────────────────────────
            val rawLabel    = lastClickedLabels[packageName]?.takeIf { it.isNotEmpty() }
            val actionLabel = sanitizeEdgeLabel(rawLabel, fallback = screenTitle)

            if (previousScreenId != null) {
                db.upsertTransition(previousScreenId, screenId, actionLabel, "CLICK")
                Log.i(TAG, "[FORWARD] $previousScreenId -[\"$actionLabel\"]-> $screenId")
            } else {
                Log.i(TAG, "[FORWARD] First screen for $packageName: $screenId")
            }
            stack.addLast(screenId)
            Log.d(TAG, "[FORWARD] Stack depth for $packageName: ${stack.size}")
        }

        // Clear consumed click label
        lastClickedLabels.remove(packageName)

        // Persist screen record
        db.upsertScreen(screenId, packageName, screenTitle, rootClass, cleanElements)
    }

    // ── Screen title resolution ───────────────────────────────────────────────

    /**
     * Find the most likely header / toolbar text from the clean elements list.
     *
     * Passes:
     *   1. Non-clickable, top 20%, non-spatial, static (passes dynamic filter)
     *   2. Any element, top 20%, non-spatial, static
     *   3. [rootClass] fallback — always used if all elements are dynamic
     *
     * "Static" = length ≤ 35 chars AND passes the dynamic-data guard.
     */
    private fun resolveScreenTitle(elements: List<JSONObject>, rootClass: String): String {
        if (elements.isEmpty()) return rootClass

        val sh = try {
            Resources.getSystem().displayMetrics.heightPixels
        } catch (e: Exception) { 2400 }

        val topZoneLimit = sh * 0.20f

        fun isGood(el: JSONObject): Boolean {
            val name = el.optString("name", "").trim()
            val top  = el.optJSONObject("bounds")?.optInt("top", sh) ?: sh
            return name.isNotEmpty()
                    && name !in SPATIAL_LABELS
                    && top <= topZoneLimit
                    && isStaticTitle(name)
        }

        // Pass 1: non-clickable + good
        elements.firstOrNull { !it.optBoolean("is_clickable", false) && isGood(it) }
            ?.optString("name", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        // Pass 2: any (including clickable) + good
        elements.firstOrNull { isGood(it) }
            ?.optString("name", "")?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }

        return rootClass
    }

    // ── Dynamic data guard ────────────────────────────────────────────────────

    /**
     * Returns true if [text] is a valid, static screen title (not dynamic content).
     * Used for both screen titles and edge labels.
     *
     * Dynamic content that is rejected:
     *   - Phone numbers          (+91 98765 43210)
     *   - Dates                  (Aug 14, 8/14/26)
     *   - Times                  (10:50 AM)
     *   - Relative times         (5 min ago)
     *   - Purely numeric strings (1234, 98.6K)
     *   - Very long strings (> 35 chars) — likely a message preview or contact bio
     */
    private fun isStaticTitle(text: String): Boolean {
        if (text.length > 35)                       return false
        if (REGEX_PURELY_NUMERIC.matches(text))      return false
        if (REGEX_PHONE.containsMatchIn(text))       return false
        if (REGEX_DATE_NUMERIC.containsMatchIn(text)) return false
        if (REGEX_DATE_ALPHA.containsMatchIn(text))  return false
        if (REGEX_TIME.containsMatchIn(text))        return false
        if (REGEX_RELATIVE_TIME.containsMatchIn(text)) return false
        return true
    }

    /**
     * Sanitize a potential edge label for use in the graph.
     * If [raw] is null or fails the static-title check, returns [fallback].
     * Keeps contact names that are clearly just names (short, no digits) intact.
     */
    private fun sanitizeEdgeLabel(raw: String?, fallback: String): String {
        if (raw.isNullOrEmpty()) return fallback
        if (raw.length > 40)    return fallback
        if (REGEX_PHONE.containsMatchIn(raw))        return fallback
        if (REGEX_DATE_NUMERIC.containsMatchIn(raw)) return fallback
        if (REGEX_TIME.containsMatchIn(raw))         return fallback
        if (REGEX_PURELY_NUMERIC.matches(raw))       return fallback
        return raw
    }

    // ── App display name ──────────────────────────────────────────────────────

    /**
     * Map a package name to a human-readable app name.
     * Unknown packages: derive from the last segment, splitting camelCase / underscores.
     *   "com.foobar.myapp"      → "Myapp"
     *   "com.google.android.gm" → "Gm"  ← covered by KNOWN_APP_NAMES above
     */
    fun getAppDisplayName(packageName: String): String =
        KNOWN_APP_NAMES[packageName]
            ?: packageName.substringAfterLast('.')
                .split(Regex("(?<=[a-z])(?=[A-Z])|_"))
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

    // ── nav_graph.json hierarchical export ───────────────────────────────────

    /**
     * Dumps the ENTIRE known graph (all apps) into a hierarchical JSON file.
     * Called after every graph-changing event so the user always has an up-to-date
     * snapshot to pull and inspect.
     *
     * Structure: see class KDoc above.
     */
    private fun writeNavGraphSnapshot() {
        try {
            val graphJson = db.dumpHierarchicalGraph(::getAppDisplayName)

            // Annotate with current in-memory stacks for all active packages
            val activeStacks = JSONObject()
            for ((pkg, stack) in navigationStacks) {
                if (stack.isEmpty()) continue
                val stackArr = JSONArray()
                stack.forEach { stackArr.put(it) }
                activeStacks.put(pkg, stackArr)
            }
            graphJson.put("active_stacks", activeStacks)

            navGraphFile.writeText(graphJson.toString(2), Charsets.UTF_8)

            val totalApps    = graphJson.optInt("total_apps", 0)
            val totalScreens = (0 until (graphJson.optJSONArray("apps")?.length() ?: 0))
                .sumOf { graphJson.optJSONArray("apps")!!.getJSONObject(it).optInt("screen_count", 0) }
            Log.i(TAG, "nav_graph.json: $totalApps apps, $totalScreens screens total")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write nav_graph.json: ${e.message}", e)
        }
    }
}
