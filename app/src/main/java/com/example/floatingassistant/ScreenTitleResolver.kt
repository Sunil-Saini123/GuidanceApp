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
        "delete", "clear", "search settings", "loading",
        "touch outside", "touch_outside", "touch outside to dismiss",
        "scrim", "backdrop", "overlay", "dialog_scrim", "content_scrim",
        "tap to paste", "search box", "floa", "adanimationview"
    )

    private val REGEX_SIM_CARD = Regex("""(?i)\b(sim\s*\d|no\s*sim|slot\s*\d|not\s*set|emergency\s*calls)\b""")
    private val REGEX_DYNAMIC_STATUS = Regex("""(?i)\b(online|typing\.\.\.|last seen|recording\.\.\.|connecting\.\.\.|offline)\b""")
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

            // ── PASS 1: Toolbar / Header Container Supremacy ─────────────────────
            val toolbarTitle = findTitleInToolbar(rootNode, topThreshold, allocatedNodes, cleanApp)
            if (!toolbarTitle.isNullOrBlank()) {
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
                val text = (node.text ?: node.contentDescription)?.toString()?.trim()
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
    fun canonicalizeTitle(title: String, packageName: String? = null): String {
        val trimmed = title.trim()
        val lower = trimmed.lowercase()
        if (lower == "oplus settings homepage" ||
            lower == "settings homepage" ||
            lower == "oplus settings" ||
            lower == "coloros settings" ||
            lower == "sec settings" ||
            lower == "miui settings") {
            return "Settings"
        }
        return trimmed
    }
}
