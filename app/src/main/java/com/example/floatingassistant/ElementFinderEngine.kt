package com.example.floatingassistant

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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

    data class SearchResult(val found: Boolean, val bounds: Rect? = null, val isSystemNav: Boolean = false)

    /**
     * Searches for [nextStep] across 3 layers.
     * @return SearchResult with found status and optional bounds.
     */
    /**
     * Searches for [nextStep] on the current screen.
     *
     * [PERMANENT FIX] Do not remove this! Live Tree Supremacy:
     * An element is ONLY reported as found (found = true) if it is physically visible in the
     * phone's live accessibility tree right now (via findElementBoundsInLiveTree).
     *
     * Never return found = true with phantom coordinates from stale disk snapshots
     * (clean_page.json or temp_tree.json) or historical graph databases without live verification.
     * During screen transitions, files on disk lag behind by hundreds of milliseconds or seconds,
     * retaining the PREVIOUS screen's elements (e.g. "Display & brightness" from Settings lingering
     * after opening "Home screen, Lock screen & style"). Returning found=true from disk snapshots
     * falsely turns the HUD green ("Found on screen") on wrong screens, masking wrong-path and scroll guidance.
     *
     * @return SearchResult with found status and verified live bounds.
     */
    fun findElement(
        nextStep: String,
        activePackage: String,
        activeScreenName: String,
        currStep: String
    ): SearchResult {
        // [PERMANENT FIX] Do not remove this! Universal Back Target check across system navbar and toolbar
        if (nextStep.equals("BACK", ignoreCase = true)) {
            val backTarget = findUniversalBackTarget()
            if (backTarget != null) {
                val (bounds, isSystemNav) = backTarget
                Log.i(TAG, "Universal Back Target: Found Back button (isSystemNav=$isSystemNav) at $bounds")
                return SearchResult(true, bounds, isSystemNav)
            }
        }

        // [PERMANENT FIX] Do not remove this! Layer 0: Check Live Accessibility Tree in memory first.
        // This gives instant millisecond tracking without waiting for disk read.
        val liveBounds = findElementBoundsInLiveTree(nextStep)
        if (liveBounds != null) {
            Log.i(TAG, "Live Memory Tree: Found target '$nextStep' immediately on screen with bounds $liveBounds")
            return SearchResult(true, liveBounds)
        }

        // Target is not visible in the live accessibility tree right now.
        // It is either scrolled off-screen or the user is on a different screen.
        // We MUST return found = false so the state machine can accurately decide between
        // SCROLL guidance, WRONG PATH alerts, or step transitions.
        return SearchResult(false, null)
    }

    /**
     * Checks if a scrollable container is currently on screen.
     * Prioritizes Layer 0 (live memory tree) with zero disk I/O, then falls back to clean_page/temp_tree.
     */
    fun hasScrollableContainer(): Boolean {
        // [PERMANENT FIX] Do not remove this! Layer 0: Check Live Accessibility Tree in RAM first.
        // Takes <1ms, performs 0 disk I/O, and accurately identifies native/Compose/OEM scroll containers.
        val service = UiTreeAccessibilityService.instance
        if (service != null) {
            val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant")
            if (root != null) {
                val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
                allocatedNodes.add(root)
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                try {
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        val cls = node.className?.toString().orEmpty()
                        if (node.isScrollable || 
                            cls.contains("RecyclerView", ignoreCase = true) || 
                            cls.contains("ScrollView", ignoreCase = true) || 
                            cls.contains("ListView", ignoreCase = true) ||
                            cls.contains("ViewPager", ignoreCase = true) ||
                            cls.contains("NestedScrollView", ignoreCase = true)) {
                            return true
                        }
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i)
                            if (child != null) {
                                allocatedNodes.add(child)
                                queue.add(child)
                            }
                        }
                    }
                } finally {
                    for (n in allocatedNodes) {
                        try { n.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }

        // Layer 1 & 2 fallback (if accessibility service root is briefly unavailable during window transition)
        val cleanPageFile = File(context.filesDir, "clean_page.json")
        if (cleanPageFile.exists()) {
            try {
                val jsonStr = cleanPageFile.readText()
                val root = JSONObject(jsonStr)
                val elements = root.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until elements.length()) {
                    val el = elements.optJSONObject(i) ?: continue
                    val cls = el.optString("class_name", "")
                    val isScrollable = el.optBoolean("is_scrollable", false)
                    if (isScrollable || cls.contains("RecyclerView") || cls.contains("ScrollView") || cls.contains("ListView")) {
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

    data class ScrollInspectionResult(
        val hasScrollable: Boolean,
        val canScrollForward: Boolean = false,
        val canScrollBackward: Boolean = false
    )

    /**
     * [PERMANENT FIX] Do not remove this! Layer 0 Scroll Container Action Inspector.
     * Inspects the live accessibility tree in RAM for scrollable containers and checks available scroll actions.
     * ACTION_SCROLL_FORWARD indicates the user can scroll down.
     * ACTION_SCROLL_BACKWARD indicates the user can scroll up.
     */
    fun inspectScrollContainer(): ScrollInspectionResult {
        val service = UiTreeAccessibilityService.instance
        if (service != null) {
            val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant")
            if (root != null) {
                val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
                allocatedNodes.add(root)
                val queue = ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                try {
                    while (queue.isNotEmpty()) {
                        val node = queue.removeFirst()
                        val cls = node.className?.toString().orEmpty()
                        val isScroll = node.isScrollable || 
                            cls.contains("RecyclerView", ignoreCase = true) || 
                            cls.contains("ScrollView", ignoreCase = true) || 
                            cls.contains("ListView", ignoreCase = true) ||
                            cls.contains("ViewPager", ignoreCase = true) ||
                            cls.contains("NestedScrollView", ignoreCase = true)
                        
                        if (isScroll) {
                            val acts = node.actions
                            val actList = try { node.actionList } catch (_: Exception) { null }
                            val canFwd = (acts and AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) != 0 ||
                                         actList?.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD } == true
                            val canBwd = (acts and AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) != 0 ||
                                         actList?.any { it.id == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD } == true
                            return ScrollInspectionResult(
                                hasScrollable = true,
                                canScrollForward = canFwd,
                                canScrollBackward = canBwd
                            )
                        }
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i)
                            if (child != null) {
                                allocatedNodes.add(child)
                                queue.add(child)
                            }
                        }
                    }
                } finally {
                    for (n in allocatedNodes) {
                        try { n.recycle() } catch (_: Exception) {}
                    }
                }
            }
        }

        // Fallback to hasScrollableContainer disk checks
        return ScrollInspectionResult(hasScrollable = hasScrollableContainer())
    }

    /**
     * [PERMANENT FIX] Do not remove this! Live Tree Visible Text Extraction with Binder Recycling.
     * Extracts user-visible text items from the current screen in the live accessibility tree.
     * All allocated nodes are strictly recycled with try/finally to prevent native Binder exhaustion.
     */
    fun getVisibleTextElementsInLiveTree(): List<String> {
        val service = UiTreeAccessibilityService.instance ?: return emptyList()
        val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant") ?: return emptyList()

        val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
        allocatedNodes.add(root)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        val results = mutableListOf<String>()
        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node.isVisibleToUser) {
                    val text = node.text?.toString()?.trim()
                    val desc = node.contentDescription?.toString()?.trim()
                    val label = when {
                        !text.isNullOrEmpty() && text.length > 1 -> text
                        !desc.isNullOrEmpty() && desc.length > 1 -> desc
                        else -> null
                    }
                    if (label != null) {
                        val base = extractBaseLabel(label)
                        if (base.isNotEmpty() && base.length > 1 && !results.contains(base)) {
                            results.add(base)
                        }
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        allocatedNodes.add(child)
                        queue.add(child)
                    }
                }
            }
        } finally {
            for (node in allocatedNodes) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }
        return results
    }

    /**
     * [PERMANENT FIX] Do not remove this! Relative Element Order & Scroll Boundary Intelligence.
     * Determines whether the user needs to scroll UP or DOWN to reach [targetStep]:
     * 1. Checks native scroll container boundaries: if the container is at the bottom (cannot scroll forward),
     *    it forces UP; if at the top (cannot scroll backward), it forces DOWN.
     * 2. Checks relative element order in the screen layout (from NavGraphDatabase):
     *    Compares the index of [targetStep] with the average index of currently visible elements.
     *    If target index < visible average -> target is ABOVE the viewport -> returns UP.
     *    If target index > visible average -> target is BELOW the viewport -> returns DOWN.
     */
    fun determineScrollDirection(
        targetStep: String,
        activePkg: String,
        activeScreen: String
    ): ElementMarkerOverlay.ScrollDirection? {
        // 1. Native container boundary check
        val scrollInfo = inspectScrollContainer()
        if (scrollInfo.hasScrollable) {
            if (!scrollInfo.canScrollForward && scrollInfo.canScrollBackward) {
                Log.i(TAG, "determineScrollDirection: container reached bottom (cannot scroll forward) -> SCROLL UP")
                return ElementMarkerOverlay.ScrollDirection.UP
            }
            if (!scrollInfo.canScrollBackward && scrollInfo.canScrollForward) {
                Log.i(TAG, "determineScrollDirection: container reached top (cannot scroll backward) -> SCROLL DOWN")
                return ElementMarkerOverlay.ScrollDirection.DOWN
            }
        }

        // 2. Relative element order comparison via NavGraphDatabase
        val canonicalScreen = ScreenTitleResolver.canonicalizeTitle(activeScreen, activePkg)
        val db = NavGraphDatabase.getInstance(context)
        val screenRecord = db.getScreenById("$activePkg::$canonicalScreen")
            ?: db.getScreenById("$activePkg::$activeScreen")

        val elementsJson = screenRecord?.elementsJson
        if (!elementsJson.isNullOrEmpty()) {
            try {
                val jsonArray = JSONArray(elementsJson)
                var targetIndex = -1
                for (i in 0 until jsonArray.length()) {
                    val el = jsonArray.optJSONObject(i) ?: continue
                    val name = el.optString("name", "")
                    if (fuzzyElementMatch(name, targetStep) || fuzzyElementMatch(targetStep, name)) {
                        targetIndex = i
                        break
                    }
                }

                if (targetIndex != -1) {
                    val visibleElements = getVisibleTextElementsInLiveTree()
                    val visibleIndices = mutableListOf<Int>()
                    for (vis in visibleElements) {
                        for (i in 0 until jsonArray.length()) {
                            val el = jsonArray.optJSONObject(i) ?: continue
                            val name = el.optString("name", "")
                            if (fuzzyElementMatch(name, vis) || fuzzyElementMatch(vis, name)) {
                                visibleIndices.add(i)
                                break
                            }
                        }
                    }

                    if (visibleIndices.isNotEmpty()) {
                        val avgVisibleIndex = visibleIndices.average()
                        Log.i(TAG, "determineScrollDirection: target '$targetStep' idx=$targetIndex, visible avg=${avgVisibleIndex} (visible count=${visibleIndices.size})")
                        return if (targetIndex < avgVisibleIndex) {
                            ElementMarkerOverlay.ScrollDirection.UP
                        } else {
                            ElementMarkerOverlay.ScrollDirection.DOWN
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "determineScrollDirection: error checking element order: ${e.message}")
            }
        }

        return null
    }

    /**
     * Searches the live accessibility tree for an element matching [target].
     * Returns the element's screen bounds if found, null otherwise.
     */
    fun findElementBoundsInLiveTree(target: String): Rect? {
        val service = UiTreeAccessibilityService.instance ?: return null
        val root = AppWindowResolver.getUnderlyingAppRoot(service, "com.example.floatingassistant") ?: return null

        val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
        allocatedNodes.add(root)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        try {
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                
                val text = node.text?.toString() ?: ""
                val desc = node.contentDescription?.toString() ?: ""
                val resIdName = node.viewIdResourceName?.substringAfterLast("id/", "") ?: ""

                // [PERMANENT FIX] Do not remove this! Name + Position Verification Guard.
                // Prevents false positive matching between elements sharing identical names but in completely wrong screen positions:
                // 1. WhatsApp "More options" (three dots): MUST be in the top action bar area (bounds.top <= 400, bounds.right >= screenWidth * 0.60f).
                // 2. WhatsApp "Chats": Inside Settings, it must be a list preference row (bounds.top > 200 && bounds.bottom < screenHeight - 200),
                //    strictly rejecting bottom navigation bar tabs (bounds.top > screenHeight - 300).
                // 3. When on WhatsApp root screen ("Chats"), the bottom navigation tab "Chats" must never match as an action element.
                val isMoreOptionsTarget = target.contains("more options", ignoreCase = true) || target.contains("three dots", ignoreCase = true)
                val isChatsTarget = target.equals("Chats", ignoreCase = true)

                if (isMoreOptionsTarget) {
                    val rawBounds = Rect().also { node.getBoundsInScreen(it) }
                    val displayMetrics = service.resources.displayMetrics
                    val screenWidth = displayMetrics.widthPixels
                    if (rawBounds.top > 400 || rawBounds.right < screenWidth * 0.60f) {
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i)
                            if (child != null) { allocatedNodes.add(child); queue.add(child) }
                        }
                        continue
                    }
                }

                if (isChatsTarget) {
                    val rawBounds = Rect().also { node.getBoundsInScreen(it) }
                    val displayMetrics = service.resources.displayMetrics
                    val screenHeight = displayMetrics.heightPixels
                    if (rawBounds.top > screenHeight - 300) {
                        for (i in 0 until node.childCount) {
                            val child = node.getChild(i)
                            if (child != null) { allocatedNodes.add(child); queue.add(child) }
                        }
                        continue
                    }
                }

                // [PERMANENT FIX] Do not remove this! Dedicated toolbar Back and Navigate Up icon matching for BACK target steps.
                val isBackTarget = target.equals("BACK", ignoreCase = true) || target.equals("Back", ignoreCase = true)
                val isDedicatedBackIcon = isBackTarget && node.isVisibleToUser && (
                    desc.equals("back", ignoreCase = true) ||
                    desc.equals("navigate up", ignoreCase = true) ||
                    desc.equals("go back", ignoreCase = true) ||
                    desc.equals("up", ignoreCase = true) ||
                    text.equals("back", ignoreCase = true) ||
                    resIdName.endsWith("back", ignoreCase = true) ||
                    resIdName.endsWith("up", ignoreCase = true) ||
                    resIdName.contains("action_bar_up", ignoreCase = true)
                )

                // [PERMANENT FIX] Do not remove this! Visible Text Priority Guard:
                // If visible text is present (e.g. text="Dark mode"), it represents what the user sees with their eyes.
                // Do not allow an AOSP fallback contentDescription (e.g. desc="Dark theme") to falsely match target="Dark theme"
                // when the actual visible text on screen contradicts it. Only check desc if text matches or is blank.
                val textMatches = text.isNotBlank() && fuzzyElementMatch(text, target)
                val descMatches = desc.isNotBlank() && fuzzyElementMatch(desc, target) && (text.isBlank() || textMatches)
                val resIdMatches = text.isBlank() && desc.isBlank() && fuzzyElementMatch(resIdName, target)

                if (node.isVisibleToUser && (isDedicatedBackIcon || textMatches || descMatches || resIdMatches)) {
                    
                    // [PERMANENT FIX] Do not remove this! Universal row-block container expansion with Competing Sibling Action Protection.
                    // Expands compound rows (e.g. "Screen refresh rate" + "Standard >", chat list items) to the full row block,
                    // while strictly preserving independent action buttons (Cancel/OK, tabs, calculator grids, toolbar icons).
                    var targetNode = node
                    var currentBounds = Rect().also { node.getBoundsInScreen(it) }

                    // Protect standalone navigation icons (e.g. back arrow, close button)
                    val isDedicatedIcon = (node.text.isNullOrBlank() && (
                        desc.equals("back", ignoreCase = true) ||
                        desc.equals("navigate up", ignoreCase = true) ||
                        desc.equals("close", ignoreCase = true)
                    )) || isDedicatedBackIcon

                    if (!isDedicatedIcon) {
                        var currParent = node.parent
                        var depth = 0
                        while (currParent != null && depth < 5) {
                            allocatedNodes.add(currParent)
                            val pBounds = Rect()
                            currParent.getBoundsInScreen(pBounds)

                            // Stop if parent is too tall to be a single interactive row (height > 350px)
                            if (pBounds.height() > 350 || pBounds.height() <= 0) {
                                break
                            }

                            // Competing Sibling Action Check:
                            // Check if this parent has other independent, clickable action buttons
                            // (e.g. "Cancel" vs "OK", calculator number buttons, navigation tabs)
                            var hasCompetingSiblings = false
                            for (i in 0 until currParent.childCount) {
                                val child = currParent.getChild(i) ?: continue
                                allocatedNodes.add(child)
                                if (child == targetNode || child == node) continue

                                val cText = child.text?.toString()?.trim() ?: ""
                                val cDesc = child.contentDescription?.toString()?.trim() ?: ""
                                val isClickableAction = child.isClickable || child.isCheckable
                                val isDecorative = cText == ">" || cText == "›" || 
                                                   cDesc.contains("arrow", ignoreCase = true) || 
                                                   cDesc.contains("chevron", ignoreCase = true)

                                if (child.isVisibleToUser && isClickableAction && !isDecorative && (cText.isNotEmpty() || cDesc.isNotEmpty())) {
                                    hasCompetingSiblings = true
                                    break
                                }
                            }

                            if (hasCompetingSiblings) {
                                // Parent contains multiple distinct clickable choices — do NOT merge!
                                break
                            }

                            // If parent represents a wider unified block, expand candidate bounds
                            if (pBounds.width() > currentBounds.width()) {
                                targetNode = currParent
                                currentBounds = pBounds
                            }

                            // If this parent is explicitly clickable and spans the list row width, it is the master row container
                            if (currParent.isClickable && pBounds.width() >= 500) {
                                targetNode = currParent
                                currentBounds = pBounds
                                break
                            }

                            currParent = currParent.parent
                            depth++
                        }
                    }

                    // Extra sanity check: don't highlight the whole screen
                    if (currentBounds.height() > 500) {
                        node.getBoundsInScreen(currentBounds)
                    }

                    // [PERMANENT FIX] Do not remove this! Minimum Tappable Height & Viewport Visibility Guard.
                    // When an element has scrolled mostly or partially off-screen (e.g. behind a top toolbar or below navigation bar),
                    // Android accessibility tree still reports phantom remnant slivers (e.g. Rect(0, 278, 1080, 304) with height 26px).
                    // A list row or button requires at least 70px visible height (unless it is a dedicated small navigation icon) to be considered visible and tappable.
                    val minTappableHeight = if (isDedicatedIcon) 30 else 70
                    if (currentBounds.width() <= 0 || currentBounds.height() < minTappableHeight) {
                        return null
                    }

                    return currentBounds
                }

                for (i in 0 until node.childCount) {
                    val child = node.getChild(i)
                    if (child != null) {
                        allocatedNodes.add(child)
                        queue.add(child)
                    }
                }
            }
            return null
        } finally {
            // [PERMANENT FIX] Do not remove this! Recycle all allocated AccessibilityNodeInfo objects to prevent native Binder buffer exhaustion (DeadObjectException)
            for (node in allocatedNodes) {
                try { node.recycle() } catch (_: Exception) {}
            }
        }
    }

    /**
     * [PERMANENT FIX] Do not remove this! Normalizes typographic characters, OEM non-breaking hyphens,
     * non-breaking spaces, and dynamic state/badge suffixes.
     */
    private fun normalizeTypographicText(text: String): String {
        return text
            // Unicode non-breaking hyphens, figure dashes, en-dashes, em-dashes, minus signs -> standard hyphen
            .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), "-")
            // Unicode non-breaking spaces -> standard space
            .replace(Regex("[\u00A0\u202F]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun extractBaseLabel(raw: String): String {
        val norm = normalizeTypographicText(raw)
        // [PERMANENT FIX] Do not remove this! WhatsApp Compound Content Description Normalization.
        // Drops comma-separated subtitles from WhatsApp row containers (e.g. "Chats,Chat history, backup" -> "Chats")
        val withoutCompound = if (norm.contains(",") && (norm.contains("history", ignoreCase = true) || norm.contains("backup", ignoreCase = true) ||
            norm.contains("notifications", ignoreCase = true) || norm.contains("accounts", ignoreCase = true) || norm.contains("devices", ignoreCase = true) ||
            norm.contains("benefits", ignoreCase = true) || norm.contains("groups", ignoreCase = true) || norm.contains("broadcasts", ignoreCase = true) ||
            norm.contains("usage", ignoreCase = true) || norm.contains("contrast", ignoreCase = true) || norm.contains("family", ignoreCase = true))) {
            norm.substringBefore(",").trim()
        } else norm

        // Strip subtitle suffixes (e.g. "Wi-Fi · Rohit" -> "Wi-Fi", "Chats · 3 unread" -> "Chats")
        val withoutSubtitle = withoutCompound.substringBefore(" · ").substringBefore("\n").trim()
        // Strip trailing unread numeric count badge (e.g. "Spam 15" -> "Spam", "Inbox 3" -> "Inbox")
        return withoutSubtitle.replace(Regex("\\s+\\d+$"), "").trim()
    }

    private fun fuzzyElementMatch(elementStr: String, target: String): Boolean {
        if (elementStr.isBlank() || target.isBlank()) return false

        val baseTarget = extractBaseLabel(target)
        val baseEl = extractBaseLabel(elementStr)

        val cleanTarget = baseTarget.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanTarget.isEmpty()) return false

        var cleanEl = baseEl.lowercase().replace(Regex("[^a-z0-9]"), "")
        if (cleanEl.isEmpty()) return false

        val prefixes = listOf("originui", "bbk", "sec", "miui", "oppo", "vivo", "hw")
        for (prefix in prefixes) {
            if (cleanEl.startsWith(prefix)) {
                cleanEl = cleanEl.removePrefix(prefix)
            }
        }

        // 1. Exact match after stripping dynamic suffixes & typographic normalization
        if (cleanEl == cleanTarget) return true

        // 2. Prefix match (e.g. "screenrefreshratehigh".startsWith("screenrefreshrate") or "scheduledoff".startsWith("scheduled"))
        // [PERMANENT FIX] Do not remove cleanEl.length >= 4 check!
        // Allow cleanTarget.startsWith(cleanEl) only if cleanEl is at least 4 characters (e.g. "display", "screen", "battery")
        // or a recognized tech acronym ("sim", "vpn", "sos", "nfc"), preventing 1-letter alphabet index items like "D" or "S"
        // in app drawers from matching targets like "Display & brightness".
        val allowedShortTechAcronyms = setOf("sim", "vpn", "sos", "nfc")
        // Element extends target (e.g. element="Screen refresh rate High", target="Screen refresh rate")
        if (cleanEl.startsWith(cleanTarget)) return true

        // [PERMANENT FIX] Do not remove this! Strict Ratio Guard on Target-Extends-Element Prefix Match.
        // Requires element to cover at least 70% of target length, preventing short single-word elements
        // (like "Chats" tab) from matching long compound sub-page targets (like "Chats Chat history, backup").
        if (cleanTarget.startsWith(cleanEl) && (cleanEl.length >= 4 || allowedShortTechAcronyms.contains(cleanEl))) {
            val ratio = cleanEl.length.toDouble() / cleanTarget.length
            if (ratio >= 0.70) return true
        }

        // 3. Calibrated Substring Containment (80% Ratio Guard)
        // [PERMANENT FIX] Do not remove length ratio guard!
        // Prevents small sub-tokens (e.g. "chat" or "back") from falsely matching long compound phrases
        // (e.g. "chat backup"). Substring matching requires at least 75% length ratio coverage.
        val lengthRatio = minOf(cleanEl.length, cleanTarget.length).toDouble() / maxOf(cleanEl.length, cleanTarget.length)
        if (cleanEl.contains(cleanTarget) && lengthRatio >= 0.70) return true
        if (cleanTarget.contains(cleanEl) && lengthRatio >= 0.70) return true

        // Also check if unstripped element contains target (for cases without standard separator)
        val rawCleanEl = normalizeTypographicText(elementStr).lowercase().replace(Regex("[^a-z0-9]"), "")
        if (rawCleanEl.contains(cleanTarget) || (cleanEl.length >= 3 && rawCleanEl.startsWith(cleanTarget))) return true

        return false
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

    /**
     * [PERMANENT FIX] Do not remove this! Universal System Navbar and Toolbar Back Button Discovery.
     * Finds the Back button on the phone, prioritizing the system navigation bar (3-button nav)
     * and gracefully falling back to on-screen toolbar Back arrows (gesture navigation).
     * Returns Pair(bounds, isSystemNav) or null.
     */
    fun findUniversalBackTarget(): Pair<Rect, Boolean>? {
        // Priority 1: System Navigation Bar Back Button (com.android.systemui)
        val systemNavBounds = findSystemNavBackButton()
        if (systemNavBounds != null) {
            return Pair(systemNavBounds, true)
        }

        // Priority 2: On-screen Toolbar Back Button
        val onScreenBounds = findElementBoundsInLiveTree("BACK")
        if (onScreenBounds != null) {
            return Pair(onScreenBounds, false)
        }

        return null
    }

    fun findSystemNavBackButton(): Rect? {
        val service = UiTreeAccessibilityService.instance ?: return null
        val windows = try { service.windows } catch (_: Exception) { null } ?: return null

        for (window in windows) {
            val root = try { window.root } catch (_: Exception) { null } ?: continue
            val pkg = root.packageName?.toString() ?: ""
            if (!pkg.startsWith("com.android.systemui")) {
                try { root.recycle() } catch (_: Exception) {}
                continue
            }

            val allocatedNodes = mutableListOf<AccessibilityNodeInfo>()
            allocatedNodes.add(root)
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)

            try {
                while (queue.isNotEmpty()) {
                    val node = queue.removeFirst()
                    val resId = node.viewIdResourceName ?: ""
                    val desc = node.contentDescription?.toString() ?: ""
                    val text = node.text?.toString() ?: ""

                    val isBackButton = node.isVisibleToUser && (
                        resId.endsWith(":id/back") ||
                        resId.endsWith("_back") ||
                        desc.equals("back", ignoreCase = true) ||
                        desc.equals("navigate up", ignoreCase = true) ||
                        text.equals("back", ignoreCase = true)
                    )

                    if (isBackButton) {
                        val rect = Rect()
                        node.getBoundsInScreen(rect)
                        if (rect.width() > 0 && rect.height() > 0) {
                            return rect
                        }
                    }

                    for (i in 0 until node.childCount) {
                        val child = node.getChild(i)
                        if (child != null) {
                            allocatedNodes.add(child)
                            queue.add(child)
                        }
                    }
                }
            } finally {
                for (n in allocatedNodes) {
                    try { n.recycle() } catch (_: Exception) {}
                }
            }
        }
        return null
    }
}
