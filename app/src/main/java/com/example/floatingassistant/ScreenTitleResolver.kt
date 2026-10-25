package com.example.floatingassistant

import android.content.res.Resources
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * ScreenTitleResolver - Universal Global Screen Title Detection
 *
 * Resolves clean, human-readable screen titles across ALL Android apps (WhatsApp, Settings,
 * YouTube, Phone, Gmail, Clock, etc.) and OEM ROM variants (ColorOS, OneUI, MIUI, AOSP).
 *
 * Priority Order:
 *   1. Sub-page Toolbar / ActionBar Supremacy (Toolbar, CollapsingToolbar, TopAppBar)
 *   2. Back-Button Anchor Alignment (Visual spatial proximity to top-left back arrow)
 *   3. Dedicated Header / Page Title Resource IDs (top 20% of screen)
 *   4. True Navigation Tabs (ONLY on root screens with no back button, inside recognized tab bars)
 *   5. App Label (from PackageManager, e.g. "Settings", "Clock") or Clean Activity fallback
 *
 * Prevents:
 *   - Hijacking SIM cards ("No SIM cardNot set") or filter chips ("Unread") as screen titles.
 *   - Depth-first search stopping at sub-category headers or search placeholders.
 *   - Disagreements between MainFilter, NavigationStateMachine, and GraphStateMachine.
 *   - Exposing internal OEM Java activity classes (e.g. "Oplus Settings Homepage") instead of "Settings".
 */
object ScreenTitleResolver {

    private const val TAG = "ScreenTitleResolver"

    private val BACK_WORDS = setOf(
        "back", "navigate up", "go back", "arrow_back", "close", "up"
    )

    private val NOISE_WORDS = setOf(
        "back", "navigate up", "go back", "arrow_back", "close", "up",
        "cancel", "done", "ok", "yes", "no", "save", "edit", "search",
        "more options", "more", "menu", "overflow", "help", "share",
        "delete", "clear", "clear all", "select all", "search settings", "loading",
        "touch outside", "touch_outside", "touch outside to dismiss",
        "scrim", "backdrop", "overlay", "dialog_scrim", "content_scrim",
        "tap to paste", "search box", "floa", "adanimationview"
    )

    private val REGEX_SIM_CARD = Regex("""(?i)\b(sim\s*\d|no\s*sim|slot\s*\d|not\s*set|emergency\s*calls)\b""")
    private val REGEX_DYNAMIC_STATUS = Regex("""(?i)\b(online|typing\.\.\.|last seen|recording\.\.\.|connecting\.\.\.|offline|message yourself)\b""")
    private val REGEX_PHONE = Regex("""\+?\d[\d\s\-]{7,}\d""")
    private val REGEX_PURELY_NUMERIC = Regex("""^\d+[\s\.\w]*$""")
    private val REGEX_TIME = Regex("""\b\d{1,2}:\d{2}\s*(am|pm)?\b""", RegexOption.IGNORE_CASE)
    private val REGEX_RELATIVE_TIME = Regex("""\b\d+\s*(s|m|h|min|mins|hr|hrs|day|days|sec|secs)\s*ago\b""", RegexOption.IGNORE_CASE)
    private val REGEX_DATE = Regex("""\b(\d{1,2}[\/\-]\d{1,2}([\/\-]\d{2,4})?|\w{3}\s+\d{1,2})\b""", RegexOption.IGNORE_CASE)

    /**
     * Resolves the clean, user-visible screen title from a live AccessibilityNodeInfo root.
     *
     * [PERMANENT FIX] Do not remove this! Universal screen title resolver across all apps.
     * The passed [rootNode] is OWNED by the caller and is NOT recycled here.
     * All child nodes allocated internally during traversal are cleanly recycled.
     */
    fun resolveTitle(
        rootNode: AccessibilityNodeInfo,
        appLabel: String,
        packageName: String,
        eventClassName: String? = null
    ): String {
        val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
        val cleanApp = appLabel.trim()
        try {
            val rootBounds = Rect()
            rootNode.getBoundsInScreen(rootBounds)
            val screenHeight = if (rootBounds.height() > 0) rootBounds.height() else {
                try { Resources.getSystem().displayMetrics.heightPixels } catch (_: Exception) { 2400 }
            }
            val topThreshold = screenHeight * 0.25f

            // [PERMANENT FIX] Do not remove this! Priority 0: Authoritative Component DNA Architecture for WhatsApp.
            // Structural layout identification replaces naive keyword scanning:
            // 1. Popup Menu ("Menu"): 3-dots popup on Chats list (New group, Linked devices, Settings)
            // 2. Chat Popup Menu ("Chat Menu"): 3-dots popup in Conversation (View contact, Wallpaper, More)
            // 3. Conversation ("<Contact Name>"): Has Back Button AND has message input (com.whatsapp:id/entry)
            // 4. Settings / Dedicated Sub-page ("Settings", "Chats Settings", "Account", etc.): Has Back Button AND NO message input
            // 5. Root Screen ("Chats", "Updates", "Communities", "Calls"): NO Back Button
            if (packageName == "com.whatsapp" || packageName == "whatsapp") {
                return resolveWhatsAppScreenDNA(rootNode, screenHeight, topThreshold, allocatedNodes, cleanApp)
            }

            // ── PASS 1: Toolbar / Header Container Supremacy ─────────────────────
            val toolbarTitle = findTitleInToolbar(rootNode, topThreshold, allocatedNodes, cleanApp)
            if (!toolbarTitle.isNullOrBlank()) {
                // [PERMANENT FIX] Do not remove this! Distinguish between a dedicated Search sub-page vs a root homepage search bar.
                // On a root screen (e.g. Settings homepage), an inline search box scrolls into view when scrolling down.
                // Without a Back Button, the screen remains the root screen (cleanApp = "Settings"), NEVER a separate "Search" screen.
                if (toolbarTitle.equals("Search", ignoreCase = true)) {
                    val hasBack = findBackButton(rootNode, screenHeight, allocatedNodes) != null
                    if (!hasBack && cleanApp.isNotBlank()) {
                        return canonicalizeTitle(cleanApp, packageName)
                    }
                }
                return canonicalizeTitle(toolbarTitle, packageName)
            }

            // ── PASS 2: Back-Button Anchor Alignment ─────────────────────────────
            val backAnchorTitle = findTitleByBackAnchor(rootNode, screenHeight, topThreshold, allocatedNodes, cleanApp)
            if (!backAnchorTitle.isNullOrBlank()) {
                return canonicalizeTitle(backAnchorTitle, packageName)
            }

            // ── PASS 3: Dedicated Title Resource IDs in Top Zone ─────────────────
            val resIdTitle = findTitleByResourceId(rootNode, topThreshold, allocatedNodes, cleanApp)
            if (!resIdTitle.isNullOrBlank()) {
                return canonicalizeTitle(resIdTitle, packageName)
            }

            // ── PASS 4: True Navigation Tab (Root Screens with NO Back Arrow) ────
            val selectedTab = findTrueNavigationTab(rootNode, topThreshold, allocatedNodes, cleanApp)
            if (!selectedTab.isNullOrBlank() && !selectedTab.equals(cleanApp, ignoreCase = true)) {
                return canonicalizeTitle(selectedTab, packageName)
            }

            // ── PASS 5: Fallback to App Label ("Settings") or Clean Activity Name ─
            // [PERMANENT FIX] Do not remove this! Prioritize user-facing app label ("Settings") over internal
            // OEM Activity names (like "Oplus Settings Homepage") on root screens.
            if (cleanApp.isNotBlank()) {
                return canonicalizeTitle(cleanApp, packageName)
            }

            val cleanCls = eventClassName?.substringAfterLast('.')?.let { cleanActivityName(it) }
            val rawFallback = cleanCls?.takeIf { it.isNotBlank() && it.lowercase() != "main" }
                ?: packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
            return canonicalizeTitle(rawFallback, packageName)
        } catch (e: Exception) {
            Log.e(TAG, "Error resolving screen title for $packageName: ${e.message}")
            return canonicalizeTitle(cleanApp.ifEmpty { packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() } }, packageName)
        } finally {
            // [PERMANENT FIX] Do not remove this! Recycle all allocated child nodes to prevent Binder exhaustion
            for (node in allocatedNodes) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Pass 1: Looks for a Toolbar / ActionBar container and extracts the primary title TextView.
     */
    private fun findTitleInToolbar(
        root: AccessibilityNodeInfo,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var depth = 0
        while (queue.isNotEmpty() && depth < 20) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.top <= topThreshold) {
                    val cls = node.className?.toString().orEmpty()
                    val resId = node.viewIdResourceName.orEmpty()

                    if (isToolbarContainer(cls, resId)) {
                        // Found a Toolbar! Search its descendants for the title text
                        val title = extractTitleFromToolbarDescendants(node, allocatedNodes, appLabel)
                        if (!title.isNullOrBlank()) return title
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return null
    }

    /**
     * Extracts title text from within a Toolbar container.
     */
    private fun extractTitleFromToolbarDescendants(
        toolbar: AccessibilityNodeInfo,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until toolbar.childCount) {
            val child = toolbar.getChild(i) ?: continue
            allocatedNodes.add(child)
            queue.add(child)
        }

        val candidates = mutableListOf<Pair<String, Int>>() // text to bounds area
        var hasSearchField = false

        var depth = 0
        while (queue.isNotEmpty() && depth < 6) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val text = node.text?.toString()?.trim()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                val area = bounds.width() * bounds.height()
                val cls = node.className?.toString().orEmpty()
                val isEditable = node.isEditable || cls.contains("EditText", ignoreCase = true)

                if (isEditable) {
                    // [PERMANENT FIX] Do not remove this! Editable text in a toolbar is user input (e.g. search query),
                    // never a static screen title. If no static header exists, name the screen "Search".
                    hasSearchField = true
                } else if (!text.isNullOrBlank() && isAcceptableTitle(text, appLabel)) {
                    // Exclude buttons or action items
                    if (!cls.contains("Button", ignoreCase = true) && !node.isClickable) {
                        candidates.add(text to area)
                    } else if (!cls.contains("ImageButton", ignoreCase = true)) {
                        candidates.add(text to area)
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }

        // Return candidate with the largest visual area (prominence), or fallback to "Search" if only an edit field was present
        return candidates.maxByOrNull { it.second }?.first ?: if (hasSearchField) "Search" else null
    }

    /**
     * Pass 2: Looks for a Back Button at top-left, then finds the title text aligned horizontally with it.
     */
    private fun findTitleByBackAnchor(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val backButton = findBackButton(root, screenHeight, allocatedNodes) ?: return null
        val backBounds = Rect()
        backButton.getBoundsInScreen(backBounds)

        // Find non-clickable text situated in the same vertical band as the back arrow
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val candidates = mutableListOf<Pair<String, Int>>()

        var depth = 0
        while (queue.isNotEmpty() && depth < 20) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.top <= topThreshold) {
                    val cls = node.className?.toString().orEmpty()
                    val isEditable = node.isEditable || cls.contains("EditText", ignoreCase = true)
                    val text = node.text?.toString()?.trim()
                    if (!isEditable && !text.isNullOrBlank() && isAcceptableTitle(text, appLabel)) {
                        // Check if in the same horizontal band and to the right of back button
                        val verticallyAligned = bounds.centerY() in (backBounds.top - 60)..(backBounds.bottom + 60)
                        val toTheRight = bounds.left >= backBounds.left
                        if (verticallyAligned && toTheRight) {
                            val area = bounds.width() * bounds.height()
                            candidates.add(text to area)
                        }
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }

        return candidates.maxByOrNull { it.second }?.first
    }

    /**
     * Pass 3: Dedicated title resource IDs in top zone (e.g. action_bar_title, toolbar_title).
     */
    private fun findTitleByResourceId(
        root: AccessibilityNodeInfo,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val TITLE_RES_KEYWORDS = arrayOf(
            "action_bar_title", "toolbar_title", "header_title",
            "collapsing_toolbar_title", "top_app_bar_title",
            "page_title", "screen_title"
        )

        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.top <= topThreshold) {
                    val resId = node.viewIdResourceName.orEmpty().lowercase()
                    val matchesId = TITLE_RES_KEYWORDS.any { resId.contains(it) }
                    if (matchesId) {
                        val text = node.text?.toString()?.trim()
                        if (!text.isNullOrBlank() && isAcceptableTitle(text, appLabel)) {
                            return text
                        }
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return null
    }

    /**
     * Pass 4: Finds true navigation tabs ONLY inside recognized tab bar containers.
     * Prevents false matches on SIM cards, radio buttons, and filter chips.
     */
    private fun findTrueNavigationTab(
        root: AccessibilityNodeInfo,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var depth = 0
        while (queue.isNotEmpty() && depth < 20) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val cls = node.className?.toString().orEmpty()
                val resId = node.viewIdResourceName.orEmpty()

                if (isNavigationTabContainer(cls, resId)) {
                    // Traverse inside tab container to find selected tab
                    val selectedTab = findSelectedChildInContainer(node, allocatedNodes, appLabel)
                    if (!selectedTab.isNullOrBlank()) return selectedTab
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return null
    }

    private fun findSelectedChildInContainer(
        container: AccessibilityNodeInfo,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        appLabel: String
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        for (i in 0 until container.childCount) {
            val child = container.getChild(i) ?: continue
            allocatedNodes.add(child)
            queue.add(child)
        }

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isSelected || node.isChecked) {
                val rawText = (node.text ?: node.contentDescription)?.toString()?.trim()
                // [PERMANENT FIX] Do not remove this! Strip unread badge suffixes (e.g. "Chats, 1 new notification") from bottom navigation tabs
                val text = if (!rawText.isNullOrBlank()) stripBadgeNoise(rawText) else null
                if (!text.isNullOrBlank() && isAcceptableTitle(text, appLabel) && text.length <= 25) {
                    return text
                }
            }
            for (c in 0 until node.childCount) {
                val child = node.getChild(c) ?: continue
                allocatedNodes.add(child)
                queue.add(child)
            }
        }
        return null
    }

    private fun findBackButton(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        allocatedNodes: MutableList<AccessibilityNodeInfo>
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.left <= 250 && bounds.top <= screenHeight * 0.25f) {
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val resId = node.viewIdResourceName.orEmpty().lowercase()
                    val cls = node.className?.toString().orEmpty()

                    val isBack = BACK_WORDS.contains(desc) || BACK_WORDS.contains(text) ||
                            resId.contains("back") || resId.contains("navigate_up") ||
                            resId.contains("btn_back") || resId.contains("up")

                    if (isBack) return node

                    // Check top-left clickable ImageButton/ImageView
                    if ((cls.contains("ImageButton", ignoreCase = true) || cls.contains("ImageView", ignoreCase = true)) &&
                        node.isClickable && bounds.left <= 120 && bounds.top <= 250 &&
                        (desc.isEmpty() && text.isEmpty())) {
                        return node
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return null
    }

    private fun isToolbarContainer(className: String, resId: String): Boolean {
        val cls = className.lowercase()
        val id = resId.lowercase()
        return cls.contains("toolbar") || cls.contains("actionbar") ||
                cls.contains("collapsingtoolbar") || cls.contains("topappbar") ||
                cls.contains("headerbar") ||
                id.contains("toolbar") || id.contains("action_bar") ||
                id.contains("collapsing_toolbar") || id.contains("top_app_bar") ||
                id.contains("header_bar")
    }

    private fun isNavigationTabContainer(className: String, resId: String): Boolean {
        val cls = className.lowercase()
        val id = resId.lowercase()
        return cls.contains("tablayout") || cls.contains("tabstrip") ||
                cls.contains("bottomnavigation") || cls.contains("navigationbar") ||
                cls.contains("tabbar") || cls.contains("tabhost") ||
                id.contains("tab_layout") || id.contains("bottom_nav") ||
                id.contains("navigation_bar") || id.contains("bottom_bar") ||
                id.contains("tabstrip")
    }

    /**
     * Checks if a text candidate is a valid, static, non-noise screen title.
     *
     * [PERMANENT FIX] Do not remove this! Never reject the app's own user-facing name (e.g. "Settings", "Clock")
     * as noise.
     */
    fun isAcceptableTitle(text: String, appLabel: String = ""): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2 || trimmed.length > 50) return false
        val lower = trimmed.lowercase()

        // If the text matches the app's own user-facing name (e.g. "Settings", "Clock"), it is always acceptable!
        if (appLabel.isNotBlank() && lower == appLabel.trim().lowercase()) return true

        if (lower in NOISE_WORDS) return false
        if (lower.contains("touch outside") || lower.contains("touch_outside")) return false
        if (REGEX_SIM_CARD.containsMatchIn(lower)) return false
        if (REGEX_DYNAMIC_STATUS.containsMatchIn(lower)) return false
        if (REGEX_PURELY_NUMERIC.matches(lower)) return false
        if (REGEX_PHONE.containsMatchIn(lower)) return false
        if (REGEX_TIME.containsMatchIn(lower)) return false
        if (REGEX_RELATIVE_TIME.containsMatchIn(lower)) return false
        if (REGEX_DATE.containsMatchIn(lower)) return false

        // Must contain at least one letter
        if (!lower.any { it.isLetter() }) return false

        return true
    }

    /**
     * Converts Android Activity class name into human-readable words.
     * [PERMANENT FIX] Do not remove this! Strips OEM prefixes (Oplus, Oppo, Vivo, Sec, Miui) and
     * internal suffixes (Homepage, Host, Tab) so "OplusSettingsHomepageActivity" becomes "Settings".
     */
    fun cleanActivityName(className: String): String {
        val NOISE_WORDS = setOf(
            "activity", "fragment", "screen", "view", "tab", "host",
            "main", "root", "container", "base", "impl", "homepage", "home"
        )
        // Strip common OEM developer prefixes
        val withoutOem = className
            .replace(Regex("^(Oplus|Oppo|Vivo|Sec|Miui|Bbk|Huawei|Hw|ColorOS)", RegexOption.IGNORE_CASE), "")

        val stripped = withoutOem
            .replace(Regex("Activity$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Fragment$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("Screen$", RegexOption.IGNORE_CASE), "")

        val words = stripped
            .split(Regex("(?<=[a-z])(?=[A-Z])|_"))
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.lowercase() !in NOISE_WORDS }

        val raw = words.joinToString(" ").trim()
        return canonicalizeTitle(raw)
    }

    /**
     * [PERMANENT FIX] Do not remove this! Canonicalize split-brain OEM screen titles.
     * Maps OEM variants like "Oplus Settings Homepage", "Settings Homepage", "Oplus Settings"
     * directly to the canonical "Settings" title.
     */
    // [PERMANENT FIX] Do not remove this! Strip notification badge counts and unread counter suffixes from screen and tab titles.
    fun stripBadgeNoise(raw: String): String {
        if (raw.isBlank()) return ""
        return raw.replace(Regex("""(?i),\s*\d+\s+new\s+(notification|message)s?"""), "")
                  .replace(Regex("""(?i),\s*\d+\s+unread"""), "")
                  .replace(Regex("""(?i)\s*\(\d+\)$"""), "")
                  .trim()
    }

    fun canonicalizeTitle(title: String, packageName: String? = null): String {
        val clean = stripBadgeNoise(title.trim())
        val lower = clean.lowercase()
        if (lower == "oplus settings homepage" ||
            lower == "settings homepage" ||
            lower == "oplus settings" ||
            lower == "coloros settings" ||
            lower == "sec settings" ||
            lower == "miui settings") {
            return "Settings"
        }
        // [PERMANENT FIX] Do not remove this! WhatsApp Root Screen Canonicalization.
        // In WhatsApp, the main action bar displays "WhatsApp", while the navigation graph and conversation list
        // are titled "Chats". Canonicalize "WhatsApp" on root and any notification-bearing "Chats, N unread" to "Chats"
        // so live tree and DB match 100%. Never match "Chats Settings" or other sub-pages!
        if ((packageName == "com.whatsapp" || packageName == "whatsapp") && (lower == "whatsapp" || lower == "whatsapp home" || lower == "chats" || lower.startsWith("chats,"))) {
            return "Chats"
        }
        // [PERMANENT FIX] Do not remove this! WhatsApp Screen Canonicalization Protection.
        // Never blindly coerce WhatsApp contact names or conversation titles containing "rohit" or "you"
        // to "Settings". Genuine WhatsApp Settings is detected hierarchically via isWhatsAppSettingsScreen(root).
        // Conversation screens retain their true contact name (e.g. "Rohit Thatikonda (You)").
        if ((packageName == "com.whatsapp" || packageName == "whatsapp") && (lower == "profile" || lower == "view profile")) {
            return "Profile"
        }
        return clean
    }

    /**
     * [PERMANENT FIX] Do not remove this! Component DNA Classifier for WhatsApp.
     * Identifies WhatsApp screen archetypes via structural layout markers:
     * 1. Menu ("Menu"): 3-dots popup on Chats list
     * 2. Chat Menu ("Chat Menu"): 3-dots popup in Conversation
     * 3. Root Screen ("Chats", "Updates", "Communities", "Calls"): NO Back Button
     * 4. Conversation ("<Contact Name>"): Has Back Button AND has message input (com.whatsapp:id/entry)
     * 5. Settings / Dedicated Sub-page ("Settings", "Chats Settings", "Account", etc.): Has Back Button AND NO message input
     */
    private fun resolveWhatsAppScreenDNA(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>,
        cleanApp: String
    ): String {
        // Step 1: Detect Popup Menus first (they overlay the screen and don't have standard headers)
        if (isWhatsAppPopupMenu(root, allocatedNodes)) {
            return "Menu"
        }
        if (isWhatsAppChatPopupMenu(root, allocatedNodes)) {
            return "Chat Menu"
        }

        // Step 2: Check for Back Button
        val backButton = findBackButton(root, screenHeight, allocatedNodes)
        val hasBackButton = backButton != null

        // Step 3: If NO back button, this is a Root Screen!
        if (!hasBackButton) {
            val tabTitle = findWhatsAppSelectedTab(root, screenHeight, allocatedNodes)
            if (!tabTitle.isNullOrBlank()) {
                return tabTitle
            }
            return "Chats"
        }

        // Step 4: Has Back Button -> Sub-page
        // [PERMANENT FIX] Do not remove this! Chats Settings preferences (Theme, Wallpaper, Enter is send, Chat backup)
        // are an authoritative sub-page, NEVER a conversation thread or root Chats list.
        if (isWhatsAppChatSettingsContent(root, allocatedNodes)) {
            val toolbarTitle = findTitleInToolbar(root, topThreshold, allocatedNodes, cleanApp)
                ?: findTitleByBackAnchor(root, screenHeight, topThreshold, allocatedNodes, cleanApp)
            if (!toolbarTitle.isNullOrBlank() && toolbarTitle.equals("Chat backup", ignoreCase = true)) {
                return "Chat backup"
            }
            return "Chats Settings"
        }

        // Check for Message Input elements (unique to conversation / chat thread)
        val hasMessageInput = findWhatsAppMessageInput(root, screenHeight, allocatedNodes)
        if (hasMessageInput) {
            val conversationTitle = findWhatsAppConversationTitle(root, screenHeight, topThreshold, allocatedNodes)
            if (!conversationTitle.isNullOrBlank()) {
                return canonicalizeTitle(conversationTitle, "com.whatsapp")
            }
            return "Chat"
        }

        // Step 5: Has Back Button + NO Message Input -> Settings, Sub-page, or Search
        // [PERMANENT FIX] Do not remove this! WhatsApp Settings Structural Supremacy.
        // On WhatsApp Settings ("Me" tab), WhatsApp displays the user's personal profile name (resId: toolbar_profile_name)
        // in the toolbar alongside the user's avatar. If the screen contains WhatsApp Settings markers
        // (me_tab_root_layout, settings_nested_scroll_view, toolbar_profile_name, or isWhatsAppSettingsContent),
        // it is authoritatively the Settings screen, NEVER the user's personal name!
        if (hasWhatsAppSettingsLayout(root, allocatedNodes) || isWhatsAppSettingsContent(root, allocatedNodes)) {
            val toolbarTitle = findTitleInToolbar(root, topThreshold, allocatedNodes, cleanApp)
                ?: findTitleByBackAnchor(root, screenHeight, topThreshold, allocatedNodes, cleanApp)
            if (!toolbarTitle.isNullOrBlank() && isWhatsAppDedicatedSubpage(toolbarTitle)) {
                if (toolbarTitle.equals("Chats", ignoreCase = true)) {
                    return "Chats Settings"
                }
                return canonicalizeTitle(toolbarTitle, "com.whatsapp")
            }
            return "Settings"
        }

        val toolbarTitle = findTitleInToolbar(root, topThreshold, allocatedNodes, cleanApp)
            ?: findTitleByBackAnchor(root, screenHeight, topThreshold, allocatedNodes, cleanApp)

        if (!toolbarTitle.isNullOrBlank()) {
            if (toolbarTitle.equals("Chats", ignoreCase = true)) {
                return "Chats Settings"
            }
            if (isWhatsAppDedicatedSubpage(toolbarTitle)) {
                return canonicalizeTitle(toolbarTitle, "com.whatsapp")
            }
            if (toolbarTitle.equals("Settings", ignoreCase = true)) {
                return "Settings"
            }
            return canonicalizeTitle(toolbarTitle, "com.whatsapp")
        }

        return "Chats"
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Selected Tab Detector for Component DNA.
     * Checks if a non-default bottom navigation tab (Updates, Communities, Calls) is selected on the root screen.
     */
    private fun findWhatsAppSelectedTab(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        allocatedNodes: MutableList<AccessibilityNodeInfo>
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.top >= screenHeight * 0.7f && node.isSelected) {
                    val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                    val resId = node.viewIdResourceName.orEmpty().lowercase()

                    if (text == "updates" || desc.contains("updates") || resId.contains("updates")) return "Updates"
                    if (text == "communities" || desc.contains("communities") || resId.contains("communities")) return "Communities"
                    if (text == "calls" || desc.contains("calls") || resId.contains("calls")) return "Calls"
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return null
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Settings Structural Layout Detector.
     * Identifies WhatsApp Settings ("Me" tab) screen via its signature layout containers:
     * me_tab_root_layout, settings_nested_scroll_view, toolbar_profile_name, or setting_payments.
     */
    private fun hasWhatsAppSettingsLayout(root: AccessibilityNodeInfo, allocatedNodes: MutableList<AccessibilityNodeInfo>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                if (resId.contains("me_tab") || resId.contains("settings_nested_scroll_view") ||
                    resId.contains("toolbar_profile_name") || resId.contains("setting_payments")) {
                    return true
                }
                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return false
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Message Input Identifier for Component DNA.
     * Detects active chat conversation / thread by identifying the message input field or voice note button.
     * Excludes wall_entry_container (Meta wall/avatar container in Settings) and all Settings screens.
     */
    private fun findWhatsAppMessageInput(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        allocatedNodes: MutableList<AccessibilityNodeInfo>
    ): Boolean {
        // [PERMANENT FIX] Do not remove this! Settings and Chats Settings screens NEVER have message input.
        if (hasWhatsAppSettingsLayout(root, allocatedNodes) || isWhatsAppChatSettingsContent(root, allocatedNodes)) {
            return false
        }

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                val cls = node.className?.toString().orEmpty()
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

                // [PERMANENT FIX] Do not remove this! Exclude wall_entry_container (Meta wall/avatar container in Settings).
                // A genuine chat input field in WhatsApp ends with ":id/entry" or "/entry" and is an EditText,
                // or has voice_note_btn, send, or conversation_text_entry.
                val isGenuineEntry = (resId.endsWith(":id/entry") || resId.endsWith("/entry") || resId.contains("conversation_text_entry")) &&
                        !resId.contains("wall_entry") && (cls.contains("EditText", ignoreCase = true) || node.isFocusable)

                if (isGenuineEntry || resId.contains("voice_note_btn") || resId.contains("input_attach_button")) {
                    return true
                }

                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.top >= screenHeight * 0.4f) {
                    // [PERMANENT FIX] Do not remove this! Only genuine editable input fields or chat action buttons can be message inputs.
                    // A static TextView containing "type a message" (e.g. "See suggested stickers as you type a message") is a preference description, NOT an input field!
                    val isEditable = cls.contains("EditText", ignoreCase = true) || node.isEditable
                    if (isEditable && !resId.contains("search")) {
                        if (resId.endsWith(":id/entry") || resId.endsWith("/entry") ||
                            resId.contains("conversation_text_entry") ||
                            text.contains("type a message") || desc.contains("type a message") ||
                            text == "message" || desc == "message") {
                            return true
                        }
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return false
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Conversation Title Extractor for Component DNA.
     * Extracts contact or group name from the conversation toolbar in a WhatsApp chat thread.
     */
    private fun findWhatsAppConversationTitle(
        root: AccessibilityNodeInfo,
        screenHeight: Int,
        topThreshold: Float,
        allocatedNodes: MutableList<AccessibilityNodeInfo>
    ): String? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var candidateTitle: String? = null

        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                val text = node.text?.toString()?.trim().orEmpty()
                val bounds = Rect()
                node.getBoundsInScreen(bounds)

                if (bounds.top <= topThreshold && bounds.bottom <= screenHeight * 0.3f && bounds.left >= 80) {
                    // [PERMANENT FIX] Do not remove this! Never mistake user's own profile name in Settings for a conversation contact.
                    if (resId.contains("toolbar_profile_name") || resId.contains("me_tab")) {
                        continue
                    }
                    if (resId.contains("conversation_contact_name") || resId.contains("conversation_title") ||
                        resId.contains("toolbar_title") || resId.contains("action_bar_title")) {
                        if (text.isNotBlank() && !isIgnoredWhatsAppTitle(text)) {
                            return text
                        }
                    }

                    val cls = node.className?.toString().orEmpty()
                    if (cls.contains("TextView", ignoreCase = true) && !node.isClickable) {
                        if (text.isNotBlank() && !isIgnoredWhatsAppTitle(text) && candidateTitle == null) {
                            candidateTitle = text
                        }
                    }
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return candidateTitle
    }

    private fun isIgnoredWhatsAppTitle(title: String): Boolean {
        val lower = title.trim().lowercase()
        return lower == "whatsapp" || lower == "online" || lower.startsWith("typing") ||
               lower.startsWith("last seen") || lower == "null" || lower.isEmpty() ||
               lower == "search" || lower == "search..."
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Chats Settings Content Detector.
     * Detects WhatsApp "Chats Settings" sub-screen via its signature preference options
     * (Theme, Wallpaper, Enter is send, Font size, Voice message transcripts, Chat backup, Transfer chats, Chat history).
     * Distinguishes Chats Settings from both the root conversation list ("Chats") and active chat threads.
     */
    private fun isWhatsAppChatSettingsContent(root: AccessibilityNodeInfo, allocatedNodes: MutableList<AccessibilityNodeInfo>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 15) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

                if (resId.contains("chat_backup") || resId.contains("chat_transfer") ||
                    resId.contains("chat_history") || resId.contains("font_size_preference") ||
                    resId.contains("voice_message_transcription") || resId.contains("sticker_suggestions") ||
                    text == "chat backup" || desc == "chat backup" ||
                    text == "transfer chats" || desc == "transfer chats" ||
                    text == "chat history" || desc == "chat history" ||
                    text == "enter is send" || desc == "enter is send" ||
                    text == "keep chats archived" || desc == "keep chats archived" ||
                    text == "chat settings" || desc == "chat settings") {
                    return true
                }

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return false
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Settings content identifier for Component DNA.
     * Checks for characteristic WhatsApp Settings categories (Account, Privacy, Avatar, Chats,
     * Notifications, Storage, App language, Help) when the user profile card is displayed in the header.
     * ONLY evaluated on screens where hasBackButton == true and hasMessageInput == false.
     */
    private fun isWhatsAppSettingsContent(root: AccessibilityNodeInfo, allocatedNodes: MutableList<AccessibilityNodeInfo>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val matchedCategories = mutableSetOf<String>()
        var depth = 0

        val settingsKeywords = setOf(
            "account", "privacy", "chats", "notifications", "storage and data",
            "storage", "linked devices", "payments", "subscriptions", "lists",
            "avatar", "help", "app language", "invite a friend"
        )

        while (queue.isNotEmpty() && depth < 20) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

                for (kw in settingsKeywords) {
                    if (text == kw || text.startsWith(kw) || text.contains(kw) ||
                        desc == kw || desc.startsWith(kw) || desc.contains(kw) ||
                        resId.contains(kw.replace(" ", "_"))) {
                        matchedCategories.add(kw)
                    }
                }

                if (matchedCategories.size >= 2) return true

                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return matchedCategories.size >= 2
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp 3-Dots Popup Menu Detector.
     * Detects popup window containing WhatsApp menu items ("menuitem_settings", "menuitem_new_group", etc. or text labels).
     */
    private fun isWhatsAppPopupMenu(root: AccessibilityNodeInfo, allocatedNodes: MutableList<AccessibilityNodeInfo>): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        var menuKeywordsMatched = 0
        while (queue.isNotEmpty() && depth < 10) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val resId = node.viewIdResourceName.orEmpty().lowercase()
                if (resId.contains("menuitem_settings") || resId.contains("menuitem_new_group") ||
                    resId.contains("menuitem_payments") || resId.contains("menuitem_new_broadcast") ||
                    resId.contains("menuitem_starred") || resId.contains("menuitem_scan_qr") ||
                    resId.contains("menuitem_linked_devices")) {
                    return true
                }
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                if (text == "new group" || text == "new community" || text == "broadcast lists" ||
                    text == "linked devices" || text == "starred messages" || text == "starred" ||
                    (text == "payments" && node.isClickable) || (text == "settings" && node.isClickable)) {
                    menuKeywordsMatched++
                    if (menuKeywordsMatched >= 2) return true
                }
                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return false
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Chat Popup Menu Detector.
     * Detects popup window containing conversation menu items ("View contact", "Media, links, and docs",
     * "Wallpaper", "Mute notifications", "Block", "Clear chat", "More", etc.) and disambiguates it from root Menu.
     */
    private fun isWhatsAppChatPopupMenu(root: AccessibilityNodeInfo, allocatedNodes: MutableList<AccessibilityNodeInfo>): Boolean {
        val chatMenuItems = setOf(
            "view contact", "media, links, and docs", "media, links", "mute notifications",
            "disappearing messages", "wallpaper", "report", "block", "clear chat", "export chat", "add shortcut", "more"
        )
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var depth = 0
        while (queue.isNotEmpty() && depth < 10) {
            val levelSize = queue.size
            for (i in 0 until levelSize) {
                val node = queue.removeFirst()
                val text = node.text?.toString()?.trim()?.lowercase().orEmpty()
                if (chatMenuItems.contains(text)) {
                    return true
                }
                val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
                if (chatMenuItems.contains(desc)) {
                    return true
                }
                for (c in 0 until node.childCount) {
                    val child = node.getChild(c) ?: continue
                    allocatedNodes.add(child)
                    queue.add(child)
                }
            }
            depth++
        }
        return false
    }

    /**
     * [PERMANENT FIX] Do not remove this! WhatsApp Dedicated Sub-Page Disambiguation.
     * Prevents dedicated WhatsApp sub-pages (Chat backup, Account, Privacy, etc.)
     * from being overridden by generic Settings detection.
     */
    private fun isWhatsAppDedicatedSubpage(title: String): Boolean {
        val lower = title.trim().lowercase()
        return lower == "chat backup" || lower == "chats settings" || lower == "chats" ||
               lower == "account" || lower == "privacy" || lower == "avatar" ||
               lower == "notifications" || lower == "storage and data" ||
               lower == "app language" || lower == "help" || lower == "invite a friend" ||
               lower == "payments" || lower == "linked devices" || lower == "lists" ||
               lower == "starred messages" || lower == "broadcast lists" || lower == "new group" ||
               lower == "theme" || lower == "wallpaper" || lower == "transfer chats" || lower == "chat history" ||
               lower == "passkeys" || lower == "security notifications" || lower == "email address" ||
               lower == "two-step verification" || lower == "change number" || lower == "request account info" ||
               lower == "add account" || lower == "delete account" || lower == "default message timer" ||
               lower == "read receipts" || lower == "groups" || lower == "live location" || lower == "calls" ||
               lower == "blocked contacts" || lower == "app lock" || lower == "chat lock"
    }
}
