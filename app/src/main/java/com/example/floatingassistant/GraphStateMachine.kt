package com.example.floatingassistant

import android.content.Context
import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
        "Bottom_Nav_Item", "Bottom_Bar", "Scrollable_List",
        // Our overlay bubble
        "Floating Assistant", "FloatingAssistant",
        // Android recents / task switcher overlay (fires under the last-active app's package)
        "No recent tasks"
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
        // ── Google / AOSP ──────────────────────────────────────────────────────
        "com.whatsapp"                        to "WhatsApp",
        "com.whatsapp.w4b"                    to "WhatsApp Business",
        "com.android.settings"               to "Settings",
        "com.google.android.youtube"          to "YouTube",
        "com.google.android.gm"               to "Gmail",
        "com.google.android.apps.messaging"   to "Messages",
        "com.android.messaging"               to "Messages",
        "com.google.android.dialer"           to "Phone",
        "com.android.contacts"               to "Contacts",
        "com.android.phone"                   to "Phone",
        "com.google.android.apps.photos"      to "Photos",
        "com.google.android.apps.maps"        to "Maps",
        "com.google.android.apps.translate"   to "Translate",
        "com.google.android.calendar"         to "Calendar",
        "com.google.android.calculator"       to "Calculator",
        "com.google.android.deskclock"        to "Clock",
        "com.google.android.apps.docs"        to "Drive",
        "com.google.android.music"            to "Music",
        // ── Social / Entertainment ──────────────────────────────────────────────
        "com.facebook.katana"                 to "Facebook",
        "com.instagram.android"               to "Instagram",
        "com.twitter.android"                 to "X (Twitter)",
        "com.snapchat.android"                to "Snapchat",
        "org.telegram.messenger"              to "Telegram",
        "com.spotify.music"                   to "Spotify",
        "com.netflix.mediaclient"             to "Netflix",
        "com.amazon.mShop.android.shopping"   to "Amazon",
        "com.zhiliaoapp.musically"            to "TikTok",
        "com.ss.android.ugc.trill"            to "TikTok",
        "com.linkedin.android"                to "LinkedIn",
        "com.microsoft.teams"                 to "Teams",
        // ── Vivo / iQOO (BBK) ──────────────────────────────────────────────────
        "com.vivo.gallery"                    to "Photos",
        "com.vivo.photos"                     to "Photos",
        "com.vivo.contacts"                   to "Contacts",
        "com.vivo.phone"                      to "Phone",
        "com.vivo.calculator"                 to "Calculator",
        "com.vivo.calendar"                   to "Calendar",
        "com.vivo.filemanager"                to "Files",
        "com.vivo.music"                      to "Music",
        "com.vivo.notes"                      to "Notes",
        "com.vivo.browser"                    to "Browser",
        "com.vivo.email"                      to "Email",
        "com.vivo.ai.assistant"               to "Jovi Assistant",
        "com.vivo.appmarket"                  to "App Store",
        "com.vivo.abe"                        to "App Store",
        "com.iqoo.phonemanager"               to "Phone Manager",
        "com.bbk.contacts"                    to "Contacts",
        // ── Samsung / One UI ───────────────────────────────────────────────────
        "com.samsung.android.contacts"        to "Contacts",
        "com.samsung.android.app.contacts"    to "Contacts",
        "com.samsung.android.gallery3d"       to "Gallery",
        "com.sec.android.gallery3d"           to "Gallery",
        "com.samsung.android.messaging"       to "Messages",
        "com.samsung.android.dialer"          to "Phone",
        "com.samsung.android.calendar"        to "Calendar",
        "com.samsung.android.calculator"      to "Calculator",
        // ── MIUI / Xiaomi ──────────────────────────────────────────────────────
        "com.miui.gallery"                    to "Gallery",
        "com.miui.contacts"                   to "Contacts",
        "com.miui.phone"                      to "Phone",
        "com.miui.calculator"                 to "Calculator",
        "com.miui.calendar"                   to "Calendar",
        "com.miui.notes"                      to "Notes",
        // ── OPPO / OnePlus / Realme ────────────────────────────────────────────
        "com.coloros.contacts"                to "Contacts",
        "com.coloros.gallery3d"               to "Gallery",
        "com.coloros.phone"                   to "Phone",
        "com.coloros.calendar"                to "Calendar",
        // ── Launchers (display only — should be filtered by MainFilter) ─────────
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

    // [PERMANENT FIX] Do not remove this! Global last screen and click tracking to capture cross-package transitions.
    // When OEM apps switch packages (e.g. Settings launching com.oplus.wirelesssettings for Wi-Fi),
    // this preserves the origin screen so the transition bridge is permanently recorded in SQLite.
    @Volatile private var globalLastScreenId: String? = null
    @Volatile private var globalLastPackageName: String? = null
    @Volatile private var globalLastClickedLabel: String? = null
    @Volatile private var globalLastClickTime: Long = 0L

    // [PERMANENT FIX] Do not remove this! In-memory element cache for Scroll Mediator.
    // Tracks previously known element names per screen so scrolls with 0 new elements are skipped.
    private val screenElementCache = HashMap<String, MutableSet<String>>()

    // [PERMANENT FIX] Do not remove this! Debounced Nav Graph JSON Snapshot on Scroll.
    // Throttles heavy disk writes during active user scrolling, but guarantees nav_graph.json is updated on idle.
    private val graphScope = CoroutineScope(Dispatchers.IO)
    private var snapshotDebounceJob: Job? = null

    private fun requestSnapshotDebounced(delayMs: Long = 1000L) {
        snapshotDebounceJob?.cancel()
        snapshotDebounceJob = graphScope.launch {
            delay(delayMs)
            writeNavGraphSnapshot()
        }
    }

    // ── Initialisation ────────────────────────────────────────────────────────

    fun init(context: Context, navGraphFile: File) {
        db                = NavGraphDatabase.getInstance(context)
        this.navGraphFile = navGraphFile
        screenElementCache.clear()
        // Prune any old Floating Assistant overlay screens from previous sessions
        db.pruneOverlayScreens()
        // [PERMANENT FIX] Do not remove this! Auto-link orphan OEM screens recorded in previous sessions
        db.repairOrphanOemTransitions()
        // [PERMANENT FIX] Do not remove this! Merge split-brain Settings screens (e.g. Oplus Settings Homepage -> Settings)
        db.mergeDuplicateSettingsScreens()
        // [PERMANENT FIX] Do not remove this! Clean dynamic edge labels and typographic non-breaking hyphens
        db.cleanDynamicEdgeLabels()
        // [PERMANENT FIX] Do not remove this! Automatically repair dead-end sinks by creating missing BACK transitions
        db.repairBidirectionalTransitions()
        // [PERMANENT FIX] Do not remove this! Deduplicate screen elements by base label and suppress redundant header titles
        db.deduplicateScreenElements()
        Log.i(TAG, "Initialised — DB: nav_graph.db  JSON: ${navGraphFile.name}")
    }

    // ── Public event API ──────────────────────────────────────────────────────

    /** Called from TYPE_VIEW_CLICKED on the main thread. */
    fun setLastClickedLabel(packageName: String, label: String) {
        lastClickedLabels[packageName] = label
        // [PERMANENT FIX] Do not remove this! Preserve global click metadata across packages.
        // When an element tap in Package A launches an Activity in Package B (e.g. Settings -> Wi-Fi),
        // this allows handleNavigation to know which label triggered the cross-package transition.
        globalLastClickedLabel = label
        globalLastPackageName = packageName
        globalLastClickTime = System.currentTimeMillis()
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
                // ── [PERMANENT FIX] SCROLL MEDIATOR DELTA FILTER ──
                // Extract element names from the current visible screen
                val incomingNames = cleanElements.mapNotNull {
                    it.optString("name", "").trim().takeIf { name -> name.isNotEmpty() }
                }
                val knownSet = screenElementCache.getOrPut(screenId) { HashSet() }
                val newElements = incomingNames.filter { it !in knownSet }

                if (newElements.isEmpty()) {
                    // Nothing changed! Completely skip disk dump and DB updates
                    Log.v(TAG, "[$eventType] Mediator: 0 new elements on $screenId — skipping redundant DB write")
                    DebugTelemetryClient.sendMediatorDiff(
                        totalVisible = incomingNames.size,
                        previouslyKnown = knownSet.size,
                        newAdded = emptyList(),
                        action = "SKIPPED_NO_CHANGE"
                    )
                    return
                }

                // New elements were revealed by the scroll!
                knownSet.addAll(newElements)
                db.upsertScreen(screenId, packageName, screenTitle, rootClass, cleanElements)
                Log.d(TAG, "[$eventType] Mediator: +${newElements.size} new elements added for $screenId: $newElements")
                
                // [PERMANENT FIX] Do not remove this! Stream updated screen elements to dashboard on scroll.
                DebugTelemetryClient.sendScreenChanged(packageName, screenTitle, cleanElements)
                DebugTelemetryClient.sendMediatorDiff(
                    totalVisible = incomingNames.size,
                    previouslyKnown = knownSet.size - newElements.size,
                    newAdded = newElements,
                    action = "DELTA_ADDED"
                )
                // Debounce snapshot write to idle state to avoid flash lag while ensuring nav_graph.json stays in sync
                requestSnapshotDebounced(1000L)
                return
            }
            else -> {  // NAVIGATION
                val knownSet = screenElementCache.getOrPut(screenId) { HashSet() }
                cleanElements.forEach { el ->
                    val name = el.optString("name", "").trim()
                    if (name.isNotEmpty()) knownSet.add(name)
                }
                handleNavigation(screenId, packageName, screenTitle, rootClass, cleanElements)
                DebugTelemetryClient.sendScreenChanged(packageName, screenTitle, cleanElements)
                // Write snapshot only on full screen navigation transitions
                writeNavGraphSnapshot()
            }
        }
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
            globalLastScreenId = screenId
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
                // [PERMANENT FIX] Do not remove this! Automatically record bidirectional BACK return transition.
                // In Android, every screen opened via a forward CLICK can be exited by pressing Back to return to previousScreenId.
                db.upsertTransition(screenId, previousScreenId, "BACK", "BACK")
                Log.i(TAG, "[FORWARD] $previousScreenId -[\"$actionLabel\"]-> $screenId (with BACK return edge)")
            } else {
                // [PERMANENT FIX] Do not remove this! Cross-package transition bridge.
                // When previousScreenId is null, check if a recent click from another package launched this screen.
                val clickDelta = System.currentTimeMillis() - globalLastClickTime
                val isRecentClick = clickDelta in 0..4000L
                val originScreenId = globalLastScreenId
                val originPackage = globalLastPackageName

                if (isRecentClick && originScreenId != null && originScreenId != screenId && originPackage != null && originPackage != packageName) {
                    val crossLabel = sanitizeEdgeLabel(rawLabel ?: globalLastClickedLabel, fallback = screenTitle)
                    // Forward CLICK transition from calling package to this screen
                    db.upsertTransition(originScreenId, screenId, crossLabel, "CLICK")
                    // Reverse BACK transition so Dijkstra knows pressing Back returns to the caller
                    db.upsertTransition(screenId, originScreenId, "BACK", "BACK")
                    Log.i(TAG, "[CROSS-PACKAGE FORWARD] $originScreenId -[\"$crossLabel\"]-> $screenId (with BACK edge, delta=${clickDelta}ms)")
                    // Reset click tracker to prevent duplicate edges
                    globalLastClickTime = 0L
                    globalLastClickedLabel = null
                } else {
                    Log.i(TAG, "[FORWARD] First screen for $packageName: $screenId")
                }
            }
            stack.addLast(screenId)
            Log.d(TAG, "[FORWARD] Stack depth for $packageName: ${stack.size}")
        }

        // Clear consumed click label
        lastClickedLabels.remove(packageName)

        // Persist screen record
        db.upsertScreen(screenId, packageName, screenTitle, rootClass, cleanElements)

        // [PERMANENT FIX] Do not remove this! Always track globally active screen
        globalLastScreenId = screenId
    }

    // ── Screen title resolution ───────────────────────────────────────────────

    /**
     * Find the most likely header / toolbar text from the clean elements list
     * and return it as a clean, single-segment screen title.
     *
     * Composite names like "Settings · Search settings" are trimmed to just
     * "Settings" (everything before the first " · " is taken).  This keeps
     * screen IDs stable and readable:  `com.android.settings::Settings`.
     *
     * Search passes:
     *   1. Non-clickable element in the top 20% of the screen — most reliable;
     *      toolbar titles are usually TextViews inside a non-clickable Toolbar.
     *   2. Any element (including clickable) in the top 20% — covers OEM layouts
     *      where the whole ActionBar is a clickable container.
     *   3. [rootClass] cleaned via [cleanRootClass] — final fallback.
     *
     * "Static" = length ≤ 35 chars AND passes the dynamic-data guard.
     */
    private fun resolveScreenTitle(elements: List<JSONObject>, rootClass: String): String {
        // Navigation-only words that should NEVER become a screen title
        val NAV_ONLY_LABELS = setOf(
            "back", "close", "next", "done", "cancel", "ok", "yes", "no",
            "skip", "forward", "continue", "dismiss", "navigate up", "search",
            "clear all", "clear", "select all",
            "touch outside", "touch_outside", "touch outside to dismiss", "scrim"
        )

        // [PERMANENT FIX] Do not remove this! Priority 0: If rootClass is already a semantic screen title resolved by ScreenTitleResolver, prioritize it to ensure DB, graph, and UI match 100%
        if (rootClass.isNotBlank() && !rootClass.contains('.') && rootClass.length <= 50 && rootClass.lowercase() !in NAV_ONLY_LABELS && ScreenTitleResolver.isAcceptableTitle(rootClass)) {
            // [PERMANENT FIX] Do not remove this! Untruncated Group Chat Title Recovery.
            // When WhatsApp conversation headers truncate long group names with an ellipsis (e.g. "Serious Final Year…"),
            // recover the full untruncated group title ("Serious Final Year Project 🫡") from the recent chat click.
            val cleanBase = rootClass.trimEnd('…', '.', ' ')
            if ((rootClass.endsWith("…") || rootClass.endsWith("...")) && cleanBase.length >= 4) {
                val clickedTitle = globalLastClickedLabel?.substringBefore(",")?.trim()
                if (!clickedTitle.isNullOrBlank() && clickedTitle.startsWith(cleanBase, ignoreCase = true) && clickedTitle.length <= 50) {
                    return ScreenTitleResolver.canonicalizeTitle(clickedTitle)
                }
            }
            return ScreenTitleResolver.canonicalizeTitle(rootClass)
        }

        // [PERMANENT FIX] Do not remove this! WhatsApp Popup Menu Title Detection.
        // If the screen contains WhatsApp popup menu items (menuitem_settings, menuitem_new_group, etc.),
        // title the screen "Menu" rather than naming it after the first item ("Menuitem New Group").
        if (elements.any { it.optString("resource_id", "").contains("menuitem_") || it.optString("name", "").lowercase().startsWith("menuitem") }) {
            return "Menu"
        }

        if (elements.isEmpty()) return cleanRootClass(rootClass)

        val sh = try {
            Resources.getSystem().displayMetrics.heightPixels
        } catch (e: Exception) { 2400 }

        val topZoneLimit = sh * 0.20f

        /** Primary label of an element — stripped of any composite suffix. */
        fun primaryName(el: JSONObject): String =
            el.optString("name", "").trim().substringBefore(" · ").trim()

        fun isGood(el: JSONObject): Boolean {
            val name = primaryName(el)
            val top  = el.optJSONObject("bounds")?.optInt("top", sh) ?: sh
            // [PERMANENT FIX] Do not remove this! Editable text / search query fields must NEVER become screen titles
            val isEditable = el.optBoolean("is_editable", false) ||
                             el.optString("class_name", "").contains("EditText", ignoreCase = true) ||
                             el.optString("resource_id", "").contains("search", ignoreCase = true)
            return name.isNotEmpty()
                    && !isEditable
                    && name !in SPATIAL_LABELS
                    && name.lowercase() !in NAV_ONLY_LABELS
                    && top.toFloat() <= topZoneLimit
                    && !name.lowercase().startsWith("menuitem")
                    && !el.optString("resource_id", "").lowercase().contains("menuitem_")
                    && isStaticTitle(name)
                    && ScreenTitleResolver.isAcceptableTitle(name)
        }

        // Pass 1: non-clickable + good  (toolbar TextViews, non-interactive headers)
        elements.firstOrNull { !it.optBoolean("is_clickable", false) && isGood(it) }
            ?.let { primaryName(it) }?.takeIf { it.isNotEmpty() }?.let { return ScreenTitleResolver.canonicalizeTitle(it) }

        // Pass 2: any element in the top zone (handles OEM clickable action bars)
        elements.firstOrNull { isGood(it) }
            ?.let { primaryName(it) }?.takeIf { it.isNotEmpty() }?.let { return ScreenTitleResolver.canonicalizeTitle(it) }

        // Pass 3: clean Activity class name
        return cleanRootClass(rootClass)
    }

    /**
     * Convert an Android Activity class name into a clean, human-readable screen title.
     * [PERMANENT FIX] Do not remove this! Delegates to ScreenTitleResolver to strip OEM prefixes (Oplus/Oppo)
     * and suffixes (Homepage/Activity) and canonicalize to standard screen titles (e.g. "Settings").
     */
    private fun cleanRootClass(className: String): String {
        val cleaned = ScreenTitleResolver.cleanActivityName(className)
        if (cleaned.isNotBlank()) return ScreenTitleResolver.canonicalizeTitle(cleaned)

        val NOISE_WORDS = setOf(
            "tab", "host", "main", "root", "container",
            "view", "page", "base", "impl", "homepage", "home"
        )
        val stripped = className
            .replace(Regex("Activity$",  RegexOption.IGNORE_CASE), "")
            .replace(Regex("Fragment$",  RegexOption.IGNORE_CASE), "")
            .replace(Regex("Screen$",    RegexOption.IGNORE_CASE), "")

        // Split on camelCase boundaries  e.g. "GalleryTab" → ["Gallery", "Tab"]
        val words = stripped
            .split(Regex("(?<=[a-z])(?=[A-Z])"))
            .map    { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in NOISE_WORDS }

        val result = words.joinToString(" ").trim().ifEmpty { className }
        return ScreenTitleResolver.canonicalizeTitle(result)
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
     * [PERMANENT FIX] Do not remove this! Normalizes typographic Unicode hyphens and strips
     * dynamic subtitles (" · Rohit") and unread badges (" 15") from recorded edge labels.
     */
    private fun sanitizeEdgeLabel(raw: String?, fallback: String): String {
        if (raw.isNullOrEmpty()) return fallback

        // Normalize Unicode non-breaking hyphens, en-dashes, em-dashes to standard hyphen
        val normalized = raw
            .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), "-")
            .replace(Regex("[\u00A0\u202F]"), " ")
            .substringBefore(" · ")
            .substringBefore("\n")
            .trim()

        // Strip trailing unread numeric badge (e.g. "Spam 15" -> "Spam")
        val stripped = normalized.replace(Regex("\\s+\\d+$"), "").trim()
        val candidate = if (stripped.isNotEmpty()) stripped else normalized

        // [PERMANENT FIX] Do not remove this! Dynamic Chat Row Edge Label Resolution.
        // In messaging apps (WhatsApp, Telegram), chat list rows contain compound metadata
        // (e.g. "Serious Final Year Project 🫡, group profile icon Serious Final Year Project 🫡 4:17 pm Vicky Pukale (VIT): Photo").
        // Extract clean chat title from comma-separated row text, matching destination screenTitle so the edge matches 100%.
        if (candidate.contains(",")) {
            val chatTitle = candidate.substringBefore(",").trim()
            if (chatTitle.length in 2..50 && (fallback.contains(chatTitle, ignoreCase = true) || chatTitle.contains(fallback.trimEnd('…', '.', ' '), ignoreCase = true))) {
                return chatTitle
            }
        }

        if (fallback.isNotBlank() && fallback.length >= 2 && candidate.contains(fallback, ignoreCase = true)) {
            return fallback
        }

        if (candidate.length > 40)                         return fallback
        if (REGEX_PHONE.containsMatchIn(candidate))        return fallback
        if (REGEX_DATE_NUMERIC.containsMatchIn(candidate)) return fallback
        if (REGEX_TIME.containsMatchIn(candidate))         return fallback
        if (REGEX_PURELY_NUMERIC.matches(candidate))       return fallback
        return candidate
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

    /** Returns the current active screen ID for [packageName] from the in-memory navigation stack. */
    fun currentScreenId(packageName: String): String? =
        navigationStacks[packageName]?.lastOrNull()

    // [PERMANENT FIX] Do not remove this! Returns the globally last active screen ID across all packages.
    fun getGlobalLastScreenId(): String? = globalLastScreenId

    // [PERMANENT FIX] Do not remove this! Returns the globally last active package name.
    fun getGlobalLastPackageName(): String? = globalLastPackageName

    /** Reverse lookup: given an app display name (e.g. "WhatsApp", "Settings", "Phone"), find the package name. */
    fun getPackageNameForApp(appName: String): String? {
        val trimmed = appName.trim()
        if (trimmed.isEmpty()) return null
        // 1. Direct match in KNOWN_APP_NAMES values
        KNOWN_APP_NAMES.entries.firstOrNull { it.value.equals(trimmed, ignoreCase = true) }?.key?.let { return it }
        // 2. If it's already a package name in KNOWN_APP_NAMES
        if (KNOWN_APP_NAMES.containsKey(trimmed)) return trimmed
        // 3. Partial / substring match in display names
        KNOWN_APP_NAMES.entries.firstOrNull {
            it.value.contains(trimmed, ignoreCase = true) || trimmed.contains(it.value, ignoreCase = true)
        }?.key?.let { return it }
        return null
    }

    /** Returns all packages that currently have an active navigation stack in memory. */
    fun getActivePackages(): Set<String> = navigationStacks.keys.toSet()

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
