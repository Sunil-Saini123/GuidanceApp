package com.example.floatingassistant

import android.content.res.Resources
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * CleanPageProcessor — Phase 2 (Universal OEM-Agnostic Pipeline)
 *
 * Reads the accumulated flat node list produced by [RawDumpWriter] and extracts
 * clean, actionable, static navigation elements, writing them to [CLEAN_FILE_NAME].
 *
 * ── Rules applied in order ────────────────────────────────────────────────────
 *
 *  Rule 1 — Prune Invalid Nodes
 *    Drop if: bounds width <= 0 OR height <= 0
 *             is_visible_to_user == false
 *             bounds are entirely off-screen (negative left+top with zero size)
 *
 *  Rule 2 — Drop Dynamic Data (Blacklist)
 *    Drop if resolved name:
 *      • is longer than 55 characters
 *      • is purely numeric
 *      • contains phone number patterns
 *      • contains date patterns  (Aug 14, 8/14, 14-08-2026)
 *      • contains time patterns  (10:50, 5:30 PM)
 *      • contains relative time  (5 min ago, 2 hours ago)
 *      • contains message-status suffixes (", unread status", " - Go to channel")
 *
 *  Rule 3 — Container Flattening (OEM duplicate prevention)
 *    If a node is is_clickable=true AND has no direct text/content_desc AND
 *    its class_name suggests a layout/container AND it has child nodes (in DFS order):
 *      • Aggregate the first meaningful texts from its descendants (up to 5)
 *      • Label the container with those aggregated texts
 *      • Mark all descendants as absorbed (do not emit them separately)
 *    This prevents duplicate entries for e.g. a clickable LinearLayout whose
 *    children are a TextView("Settings") + ImageView — we get one "Settings" entry.
 *
 *  Rule 4 — Universal Naming Fallback (OEM-agnostic)
 *    Priority 1: content_desc             (accessibility label, most reliable)
 *    Priority 2: text                     (displayed label)
 *    Priority 3: strip resource_id        (remove package prefix + OEM prefixes like
 *                                          bbk_, sec_, originui_, miui_, vivo_, oppo_,
 *                                          samsung_, huawei_, oneplus_)
 *    Priority 4: spatial label by geometry (Back_Button, Search_Bar, Bottom_Nav_Item, etc.)
 *                (only for clickable/scrollable nodes — skip otherwise)
 *
 *  Rule 5 — Name-level dedup
 *    Same name (case-insensitive) seen twice → keep the first occurrence only.
 *
 * ── Output (clean_page.json) ──────────────────────────────────────────────────
 * {
 *   "meta": {
 *     "package_name":  "com.whatsapp",
 *     "root_name":     "ConversationListActivity",
 *     "timestamp":     1692039482123,
 *     "element_count": 12
 *   },
 *   "elements": [
 *     {
 *       "name":         "Chats",
 *       "is_clickable": true,
 *       "is_scrollable":false,
 *       "class_name":   "android.widget.TextView",
 *       "resource_id":  "com.whatsapp:id/tab_title",
 *       "bounds":       { "left":0, "top":120, "right":360, "bottom":176, ... }
 *     },
 *     ...
 *   ]
 * }
 *
 * Pull: adb pull /sdcard/Android/data/com.example.floatingassistant/files/clean_page.json
 */
object CleanPageProcessor {

    private const val TAG          = "CleanPageProcessor"
    const val CLEAN_FILE_NAME      = "clean_page.json"
    private const val MAX_NAME_LEN = 55

    // ── Screen dimensions (for spatial labelling) ─────────────────────────────
    private val SCREEN_W: Int get() = Resources.getSystem().displayMetrics.widthPixels
    private val SCREEN_H: Int get() = Resources.getSystem().displayMetrics.heightPixels

    // ── OEM resource-id prefix strips ─────────────────────────────────────────
    private val OEM_PREFIXES = listOf(
        "bbk_", "sec_", "originui_", "miui_", "vivo_", "oppo_",
        "samsung_", "huawei_", "oneplus_", "xiaomi_", "realme_", "moto_"
    )

    /**
     * Words that mean "this is a view type, not a label" — stripped from the END
     * of a resource-id name so we don't emit garbage like "Settings Button" or
     * "Wifi Icon" when the element already has a perfectly good name.
     *
     * Strip only from the LAST word so "wifi_btn" → "Wifi" not "" and
     * "settings_preference_item" → "Settings Preference" (preference stays, item drops).
     */
    private val VIEW_NOISE_LAST_WORDS = setOf(
        "btn", "button", "fab", "icon", "img", "image", "iv", "tv", "et",
        "item", "row", "cell", "container", "layout", "wrapper", "view",
        "indicator", "divider", "separator", "stub"
    )

    /**
     * Common Android / Settings / OEM abbreviations → normalised display form.
     * Applied word-by-word after splitting on underscores.
     */
    private val TERM_MAP = mapOf(
        "wifi"        to "Wi-Fi",
        "wlan"        to "Wi-Fi",
        "bluetooth"   to "Bluetooth",
        "bt"          to "Bluetooth",
        "ble"         to "Bluetooth",
        "nfc"         to "NFC",
        "vpn"         to "VPN",
        "sim"         to "SIM",
        "usb"         to "USB",
        "sms"         to "SMS",
        "mms"         to "MMS",
        "mic"         to "Microphone",
        "camera"      to "Camera",
        "hotspot"     to "Hotspot",
        "aod"         to "Always-On Display",
        "dnd"         to "Do Not Disturb",
        "hdr"         to "HDR",
        "fps"         to "FPS",
        "ram"         to "RAM",
        "rom"         to "Storage",
        "cpu"         to "CPU",
        "gpu"         to "GPU",
        "gps"         to "GPS",
        "lte"         to "LTE",
        "5g"          to "5G",
        "4g"          to "4G",
        "ok"          to "OK",
        "ok"          to "OK",
        "id"          to "ID",
        "uri"         to "URI",
        "url"         to "URL",
        "ui"          to "UI",
        "ai"          to "AI",
        "hd"          to "HD",
        "sd"          to "SD",
        "otg"         to "OTG",
        "otp"         to "OTP",
        "pin"         to "PIN",
        "msg"         to "Message",
        "msgs"        to "Messages",
        "notif"       to "Notification",
        "notifs"      to "Notifications",
        "prefs"       to "Preferences",
        "pref"        to "Preference",
        "mgr"         to "Manager",
        "mngr"        to "Manager",
        "acct"        to "Account",
        "accts"       to "Accounts",
        "sys"         to "System",
        "dev"         to "Device",
        "app"         to "App",
        "apps"        to "Apps",
        "lang"        to "Language",
        "loc"         to "Location",
        "vol"         to "Volume",
        "batt"        to "Battery",
        "bat"         to "Battery",
        "disp"        to "Display",
        "fp"          to "Fingerprint",
        "fprint"      to "Fingerprint",
        "pw"          to "Password",
        "pwd"         to "Password",
        "auth"        to "Authentication",
        "sec"         to "Security",
        "priv"        to "Privacy",
        "privs"       to "Permissions",
        "perm"        to "Permission",
        "perms"       to "Permissions",
        "net"         to "Network",
        "conn"        to "Connection",
        "sync"        to "Sync",
        "upd"         to "Update",
        "updt"        to "Update",
        "info"        to "Info",
        "cfg"         to "Configuration",
        "config"      to "Settings",
        "settings"    to "Settings",
        "setting"     to "Settings",
        "general"     to "General",
        "advanced"    to "Advanced",
        "more"        to "More",
        "about"       to "About",
        "help"        to "Help",
        "support"     to "Support",
        "feedback"    to "Feedback",
        "search"      to "Search",
        "home"        to "Home",
        "back"        to "Back",
        "next"        to "Next",
        "done"        to "Done",
        "save"        to "Save",
        "cancel"      to "Cancel",
        "close"       to "Close",
        "edit"        to "Edit",
        "delete"      to "Delete",
        "add"         to "Add",
        "new"         to "New",
        "create"      to "Create",
        "share"       to "Share",
        "send"        to "Send",
        "call"        to "Call",
        "calls"       to "Calls",
        "chat"        to "Chat",
        "chats"       to "Chats",
        "msg"         to "Message",
        "status"      to "Status",
        "profile"     to "Profile",
        "contacts"    to "Contacts",
        "contact"     to "Contact",
        "photo"       to "Photo",
        "photos"      to "Photos",
        "media"       to "Media",
        "files"       to "Files",
        "file"        to "File",
        "storage"     to "Storage",
        "manage"      to "Manage",
        "manager"     to "Manager",
    )

    // ── Container class heuristics ────────────────────────────────────────────
    // A clickable node with one of these class names and no direct text is
    // treated as a container whose children provide the label.
    private val CONTAINER_SUFFIXES = listOf(
        "Layout", "ViewGroup", "Container", "Wrapper", "Frame",
        "CardView", "RecyclerView", "ListView", "ScrollView"
    )

    // ── Dynamic data regexes ──────────────────────────────────────────────────
    private val REGEX_PURELY_NUMERIC   = Regex("""^\d+([.,]\d+)?[KMBkmb]?$""")
    private val REGEX_PHONE            = Regex("""\+?\d[\d\s\-(). ]{6,}\d""")
    private val REGEX_DATE_NUMERIC     = Regex("""\b\d{1,2}[/\-]\d{1,2}([/\-]\d{2,4})?\b""")
    private val REGEX_DATE_ALPHA       = Regex(
        """(?i)\b\d{1,2}\s*(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*(\s+\d{2,4})?\b"""
    )
    private val REGEX_TIME             = Regex("""\b\d{1,2}:\d{2}(\s*[APap][Mm])?\b""")
    private val REGEX_RELATIVE_TIME    = Regex(
        """(?i)\b\d+\s*(sec(ond)?|min(ute)?|hour|day|week|month|year)s?\s*(ago)?\b"""
    )
    private val REGEX_STATUS_SUFFIX    = Regex(
        """(?i)(,\s*unread status|- go to channel|- play video|· \d+ new)"""
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Synchronously extract clean elements from [nodes].
     * Runs on the calling thread — caller is responsible for dispatching to a
     * background thread if needed.  Used by Phase 3 to avoid a file I/O round-trip.
     *
     * @param nodes       Flat DFS-ordered accumulated nodes from [RawDumpWriter].
     * @param packageName Package that owns the window (used for OEM prefix stripping).
     * @return            List of clean element [JSONObject]s ready for display / graph mapping.
     */
    fun extractSync(nodes: List<JSONObject>, packageName: String): List<JSONObject> =
        extractCleanElements(nodes, packageName)

    /**
     * Write an already-extracted clean elements list to [outputFile] asynchronously.
     * Called after [extractSync] so the same clean elements are passed to both
     * Phase 2 file output and Phase 3 graph update without computing twice.
     */
    fun writeToFile(
        scope:       CoroutineScope,
        outputFile:  File,
        elements:    List<JSONObject>,
        packageName: String,
        rootName:    String
    ) {
        val timestamp = System.currentTimeMillis()
        scope.launch(Dispatchers.IO) {
            try {
                val output = JSONObject()
                val meta   = JSONObject()
                meta.put("package_name",  packageName)
                meta.put("root_name",     rootName)
                meta.put("timestamp",     timestamp)
                meta.put("element_count", elements.size)
                output.put("meta", meta)

                val arr = JSONArray()
                elements.forEach { arr.put(it) }
                output.put("elements", arr)

                outputFile.writeText(output.toString(2), Charsets.UTF_8)
                Log.i(TAG, "Clean page written: $packageName/$rootName " +
                        "(${elements.size} elements, ${outputFile.length() / 1024}KB)")
            } catch (e: Exception) {
                Log.e(TAG, "writeToFile failed: ${e.message}", e)
            }
        }
    }

    /**
     * Combined async convenience — extract + write in one coroutine.
     * Kept for Phase 2 standalone use where Phase 3 is not involved.
     */
    fun process(
        scope:       CoroutineScope,
        outputFile:  File,
        nodes:       List<JSONObject>,
        packageName: String,
        rootName:    String
    ) {
        scope.launch(Dispatchers.IO) {
            try {
                val elements = extractCleanElements(nodes, packageName)
                writeToFile(scope, outputFile, elements, packageName, rootName)
            } catch (e: Exception) {
                Log.e(TAG, "Process failed: ${e.message}", e)
            }
        }
    }

    // ── Core extraction logic ─────────────────────────────────────────────────

    private fun extractCleanElements(
        nodes:       List<JSONObject>,
        packageName: String
    ): List<JSONObject> {
        val result    = mutableListOf<JSONObject>()
        val seenNames = HashSet<String>()

        // Track the depth threshold below which we skip (for container absorption).
        // When >= 0, all nodes at depth > skipBelowDepth are children of an absorbed container.
        var skipBelowDepth = -1

        var i = 0
        while (i < nodes.size) {
            val node  = nodes[i]
            val depth = node.optInt("depth", 0)

            // ── Container absorption boundary ─────────────────────────────────
            // When we see a node at depth <= the threshold, absorption is over.
            if (skipBelowDepth >= 0) {
                if (depth > skipBelowDepth) {
                    i++; continue       // still inside absorbed container — skip
                } else {
                    skipBelowDepth = -1 // back to sibling or parent level — resume
                }
            }

            // ── Rule 1: Prune invalid bounds / visibility ─────────────────────
            val bounds = node.optJSONObject("bounds")
            if (bounds != null) {
                val w = bounds.optInt("width",  0)
                val h = bounds.optInt("height", 0)
                if (w <= 0 || h <= 0) { i++; continue }
            }
            if (!node.optBoolean("is_visible_to_user", true)) { i++; continue }

            val isClickable  = node.optBoolean("is_clickable",  false)
            val isScrollable = node.optBoolean("is_scrollable", false)
            val text         = node.optString("text",         "").trim()
            val contentDesc  = node.optString("content_desc", "").trim()
            val className    = node.optString("class_name",   "")
            val childCount   = node.optInt("child_count",    0)

            // ── Rule 0: Drop structural layout wrappers ───────────────────────
            // Non-interactive nodes with no text and no content_desc have NO
            // user-facing identity. Their resource_id is an internal engineering
            // name (e.g. "home_dashboard_rootview", "dashboard_container", 
            // "main_content_scrollable_container") that is meaningless to users.
            // Drop them silently; their children are still visited because this is
            // NOT inside a skipBelowDepth block.
            //
            // Note: isScrollable is intentionally NOT exempted here.  A RecyclerView
            // or ScrollView with no text/content_desc is still a structural wrapper —
            // "Dashboard" (from stripResourceId) is NOT a user-facing name.
            if (!isClickable && text.isEmpty() && contentDesc.isEmpty()) {
                i++; continue
            }

            // ── Rule 3: Container Flattening ──────────────────────────────────
            // A clickable layout with no direct label — aggregate its descendants.
            if (isClickable && text.isEmpty() && contentDesc.isEmpty()
                && childCount > 0 && isContainerClass(className)
            ) {
                // Skip absorption for full-screen / near-full-screen containers.
                // These are root-level wrappers (e.g. a full-screen RelativeLayout in
                // Gallery or a CoordinatorLayout covering the whole activity).  Absorbing
                // them would collapse the ENTIRE page into one label like "Photos · Search".
                // Instead let each child be processed individually.
                val boundsW = bounds?.optInt("width",  0) ?: 0
                val boundsH = bounds?.optInt("height", 0) ?: 0
                if (boundsW > 800 && boundsH > 1400) {
                    i++; continue   // full-screen wrapper — skip, process children individually
                }

                val aggregated = aggregateDescendants(
                    nodes        = nodes,
                    fromIndex    = i + 1,
                    parentDepth  = depth,
                    parentBounds = bounds   // bounds-containment check inside
                )
                if (aggregated.isNotEmpty()) {
                    // Take the first non-dynamic label as the primary name.
                    // If there are additional DISTINCT non-status labels (e.g. tab bars),
                    // append them up to a total of 3 unique labels.
                    val primary = aggregated.first()
                    val extras  = aggregated.drop(1).filter {
                        it.lowercase() !in CONTAINER_STATUS_WORDS && it != primary
                    }.take(2)
                    val name = if (extras.isEmpty()) primary
                               else (listOf(primary) + extras).joinToString(" · ")

                    if (!isDynamic(name) && seenNames.add(name.lowercase())) {
                        result.add(buildElement(name, node, bounds, source = "container"))
                    }
                    // Absorb all descendants — they are represented by the container label
                    skipBelowDepth = depth
                    i++; continue
                }
                // No usable child text found — fall through to regular naming
            }

            // ── Rule 4: Universal naming ──────────────────────────────────────
            val name = resolveName(
                text        = text,
                contentDesc = contentDesc,
                resourceId  = node.optString("resource_id", ""),
                className   = className,
                isClickable = isClickable,
                isScrollable= isScrollable,
                bounds      = bounds,
                packageName = packageName
            ) ?: run { i++; continue }

            // ── Rule 2: Drop dynamic data ─────────────────────────────────────
            if (isDynamic(name)) { i++; continue }

            // ── Rule 5: Name-level dedup ──────────────────────────────────────
            if (seenNames.add(name.lowercase())) {
                result.add(buildElement(name, node, bounds, source = "direct"))
            }

            i++
        }

        return result
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Words that indicate an element's state/status OR a common UI control label
     * rather than its navigation identity.  Labels that are ONLY one of these words
     * are filtered from container aggregation.
     *
     * Examples prevented:
     *   • "Wi-Fi · Off"      → "Wi-Fi"      (status word "off")
     *   • "Contacts · More"  → "Contacts"   (overflow button "more")
     *
     * Note: checked case-insensitively via .lowercase() at the call site.
     */
    private val CONTAINER_STATUS_WORDS = setOf(
        // State / toggle words
        "on", "off", "yes", "no", "ok",
        "enabled", "disabled",
        "connected", "disconnected", "connecting",
        "active", "inactive",
        "available", "unavailable",
        "allowed", "blocked",
        "synced", "syncing",
        "charging", "charged",
        "loading", "searching",
        // Common UI action / control labels — not meaningful navigation labels on their own
        "more", "more options", "overflow",
        "back", "close", "cancel", "done", "dismiss"
    )


    /**
     * Collect the first few meaningful text labels from LEAF descendants only
     * (i.e. nodes with child_count == 0).  Parent/wrapper nodes that summarise
     * their children in a content_desc (e.g. "Wi-Fi, Off") are skipped — we
     * collect the atomic texts from the actual leaf TextViews instead.
     *
     * Filters applied to each leaf label:
     *   • Must be > 1 character (rejects section-header chars like "#", "A", "B")
     *   • Must not exceed MAX_NAME_LEN characters
     *   • Must not be a status-only word (On, Off, Connected, More, Back, etc.)
     *   • Must not pass isDynamic() check (phone numbers, dates, times)
     *   • Visual center must be inside the parent container's bounds (bounds check)
     *
     * The bounds check is the critical fix for OEM layouts where a bottom nav or
     * header widget appears BEFORE the main content list in DFS order (due to
     * view hierarchy ordering) — without it, aggregation would collect unrelated
     * content list items that happen to follow in the flat node list.
     *
     * Returns at most 5 distinct labels in DFS pre-order.
     */
    private fun aggregateDescendants(
        nodes:        List<JSONObject>,
        fromIndex:    Int,
        parentDepth:  Int,
        parentBounds: JSONObject? = null
    ): List<String> {
        val texts = mutableListOf<String>()
        val seen  = HashSet<String>()

        // Pre-compute parent vertical span for the containment check.
        // We use vertical center of each child (cTop+cBottom)/2 to decide if
        // a descendant is visually "inside" this container.
        val pTop    = parentBounds?.optInt("top",    -1) ?: -1
        val pBottom = parentBounds?.optInt("bottom", -1) ?: -1
        val checkBounds = pTop >= 0 && pBottom >= 0

        var j = fromIndex
        while (j < nodes.size) {
            val n     = nodes[j]
            val depth = n.optInt("depth", 0)
            if (depth <= parentDepth) break          // left the subtree

            // ── Bounds containment ────────────────────────────────────────────
            // If a descendant's vertical center is outside the parent's bounds,
            // skip it (continue, not break — valid children may follow).
            // This prevents e.g. a BottomNavigationView that appears early in DFS
            // from absorbing contact-list items rendered above it on screen.
            if (checkBounds) {
                val cb = n.optJSONObject("bounds")
                if (cb != null) {
                    val cCenter = (cb.optInt("top", 0) + cb.optInt("bottom", 0)) / 2
                    if (cCenter < pTop || cCenter > pBottom) {
                        j++; continue   // visually outside — skip, don't break
                    }
                }
            }

            // Only collect from leaf nodes — parent nodes repeat child content
            // in their content_desc (e.g. "Wi-Fi, Off" summarises two children).
            val isLeaf = n.optInt("child_count", 0) == 0
            if (isLeaf) {
                val cd    = n.optString("content_desc", "").trim()
                val text  = n.optString("text",         "").trim()
                val label = cd.ifEmpty { text }

                if (label.length > 1                              // reject "#", "A", section headers
                    && label.length <= MAX_NAME_LEN
                    && label.lowercase() !in CONTAINER_STATUS_WORDS
                    && !isDynamic(label)
                    && seen.add(label.lowercase())
                ) {
                    texts.add(label)
                    if (texts.size >= 5) break   // enough labels
                }
            }
            j++
        }
        return texts
    }

    /**
     * Resolve a human-readable name using the 4-priority fallback chain.
     * Returns null when no name can be determined and the node should be dropped.
     */
    private fun resolveName(
        text:        String,
        contentDesc: String,
        resourceId:  String,
        className:   String,
        isClickable: Boolean,
        isScrollable:Boolean,
        bounds:      JSONObject?,
        packageName: String
    ): String? {
        // Priority 1: contentDescription (best for icon buttons, OEM widgets)
        if (contentDesc.isNotEmpty()) return contentDesc

        // Priority 2: text
        if (text.isNotEmpty()) return text

        // Priority 3: strip resource_id
        if (resourceId.isNotEmpty()) {
            val stripped = stripResourceId(resourceId)
            if (stripped.isNotEmpty()) return stripped
        }

        // Priority 4: spatial label (only for interactive nodes — others are useless without names)
        if ((isClickable || isScrollable) && bounds != null) {
            return spatialLabel(bounds, className, isScrollable)
        }

        return null   // no name resolvable — drop
    }

    /**
     * Strip OEM noise from a resource-id string and produce a human-readable name.
     *
     * Steps:
     *  1. Remove package prefix: "com.android.settings:id/wifi_toggle" → "wifi_toggle"
     *  2. Remove OEM prefix (first match): "bbk_search_btn" → "search_btn"
     *  3. Remove view-type noise from the LAST word only:
     *       "search_btn"   → "search"
     *       "wifi_toggle"  → "wifi_toggle"  (toggle is meaningful, kept)
     *       "battery_item" → "battery"
     *  4. Split on underscore, apply TERM_MAP or Title Case each word:
     *       "wifi"     → "Wi-Fi"
     *       "search"   → "Search"
     *       "battery_settings" → "Battery Settings"
     *       "ram_storage"      → "RAM Storage"
     *
     * Examples:
     *   "com.bbk.launcher:id/bbk_search_btn"          → "Search"
     *   "com.android.settings:id/battery_header_item" → "Battery"
     *   "com.android.settings:id/wifi_settings"       → "Wi-Fi Settings"
     *   "com.vivo.settings:id/vivo_ram_storage"       → "RAM Storage"
     *   "com.sec.android:id/sec_bluetooth_item"       → "Bluetooth"
     */
    private fun stripResourceId(resourceId: String): String {
        // Step 1: Remove package prefix
        val afterId = if (resourceId.contains(":id/"))
            resourceId.substringAfterLast(":id/")
        else
            resourceId

        // Step 2: Remove OEM prefix (case-insensitive, first match)
        var name = afterId.lowercase()
        for (prefix in OEM_PREFIXES) {
            if (name.startsWith(prefix)) {
                name = name.drop(prefix.length)
                break
            }
        }

        // Step 3: Split on underscore, strip view-type noise from last word only
        val words = name.split("_").filter { it.isNotEmpty() }.toMutableList()
        if (words.size > 1 && words.last() in VIEW_NOISE_LAST_WORDS) {
            words.removeAt(words.lastIndex)
        }
        if (words.isEmpty()) return ""

        // Step 4: Map each word through TERM_MAP, or fall back to Title Case
        val result = words.joinToString(" ") { word ->
            TERM_MAP[word] ?: word.replaceFirstChar { it.uppercase() }
        }

        return result.trim()
    }

    /**
     * Assign a spatial label based on screen position.
     *
     * Zones (as fraction of screen dimensions):
     *   Top 12%    → header zone (back button, search bar, action bar)
     *   Bottom 12% → footer zone (bottom nav items, FAB)
     */
    private fun spatialLabel(
        bounds:      JSONObject,
        className:   String,
        isScrollable:Boolean
    ): String? {
        val sw = SCREEN_W
        val sh = SCREEN_H
        if (sw == 0 || sh == 0) return null

        val left   = bounds.optInt("left",   0)
        val top    = bounds.optInt("top",    0)
        val w      = bounds.optInt("width",  0)
        val h      = bounds.optInt("height", 0)
        val bottom = top + h

        val isTopZone    = top <= sh * 0.12
        val isBottomZone = bottom >= sh * 0.88
        val isLeftEdge   = left <= sw * 0.15
        val isRightEdge  = (left + w) >= sw * 0.85
        val isWide       = w >= sw * 0.5
        val isSquarish   = w in 1..250 && h in 1..250

        val simpleClass = className.substringAfterLast('.')

        return when {
            isScrollable                                               -> "Scrollable_List"
            isTopZone && isLeftEdge && isSquarish                      -> "Back_Button"
            isTopZone && isRightEdge && isSquarish                     -> "Menu_Button"
            isTopZone && isWide && simpleClass.contains("EditText")    -> "Search_Bar"
            isTopZone && isWide                                        -> "Action_Bar"
            isBottomZone && isSquarish                                 -> "Bottom_Nav_Item"
            isBottomZone && isWide                                     -> "Bottom_Bar"
            else                                                       -> null
        }
    }

    /**
     * Returns true if [name] should be dropped as dynamic content.
     */
    private fun isDynamic(name: String): Boolean {
        if (name.length > MAX_NAME_LEN)                    return true
        if (REGEX_PURELY_NUMERIC.matches(name))            return true
        if (REGEX_PHONE.containsMatchIn(name))             return true
        if (REGEX_DATE_NUMERIC.containsMatchIn(name))      return true
        if (REGEX_DATE_ALPHA.containsMatchIn(name))        return true
        if (REGEX_TIME.containsMatchIn(name))              return true
        if (REGEX_RELATIVE_TIME.containsMatchIn(name))     return true
        if (REGEX_STATUS_SUFFIX.containsMatchIn(name))     return true
        return false
    }

    /**
     * True if [className] suggests a container/layout type that may wrap
     * meaningful children without carrying its own label.
     */
    private fun isContainerClass(className: String): Boolean {
        val simple = className.substringAfterLast('.')
        return CONTAINER_SUFFIXES.any { simple.endsWith(it, ignoreCase = true) }
    }

    /** Build a clean element JSONObject from resolved data. */
    private fun buildElement(
        name:   String,
        node:   JSONObject,
        bounds: JSONObject?,
        source: String
    ): JSONObject {
        val el = JSONObject()
        el.put("name",          name)
        el.put("is_clickable",  node.optBoolean("is_clickable",  false))
        el.put("is_scrollable", node.optBoolean("is_scrollable", false))
        el.put("class_name",    node.optString("class_name",   ""))
        el.put("resource_id",   node.optString("resource_id",  ""))
        if (bounds != null) el.put("bounds", bounds)
        el.put("source", source)   // "direct" | "container" — useful for debugging
        return el
    }
}
