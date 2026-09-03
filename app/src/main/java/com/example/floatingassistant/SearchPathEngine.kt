package com.example.floatingassistant

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.util.PriorityQueue

/**
 * SearchPathEngine — Phase 2 (Tier 1 Local Graph Search)
 *
 * Implements shortest-path search (Dijkstra / BFS) over the local SQLite navigation graph
 * ([NavGraphDatabase]), resolving recorded screens and transitions without requiring
 * network access.
 *
 * ── Algorithm Flow ─────────────────────────────────────────────────────────────
 * 1. Target Node Resolution:
 *    • Matches [destinationScreen] directly against `screens.screen_title`
 *      (exact case-insensitive match, then substring / token overlap).
 *    • If no screen title matches, searches inside `screens.elements_json` to find
 *      which screen hosts the target element / action (e.g. "Bluetooth" switch inside
 *      "Connected devices" screen).
 *
 * 2. Start Node Resolution:
 *    • Uses [currentScreenId] from [GraphStateMachine] if the user is already inside
 *      the target app.
 *    • Fallback: finds the root / entry screen for the package (0 in-degree, earliest
 *      seen, or highest visit count).
 *
 * 3. Dijkstra Shortest-Path Search:
 *    • Operates on directed transitions in `transitions` table.
 *    • Edge weights account for traversal count: frequently traversed edges are preferred.
 *    • Cycle-safe and guarantees the shortest action sequence.
 *
 * 4. Step Reconstitution:
 *    • Produces an ordered list of action labels and a human-readable path string
 *      (e.g. "Settings -> Connected devices -> Bluetooth").
 */
object SearchPathEngine {

    private const val TAG = "SearchPathEngine"

    /**
     * Represents the outcome of a Tier 1 Local Graph Search.
     *
     * @param found          Whether a complete navigation path to the destination was found.
     * @param pathString     Formatted path string (e.g. "Settings -> Connected devices -> Bluetooth"), or null.
     * @param steps          Ordered sequence of action labels to execute.
     * @param fromScreenId   Starting screen ID.
     * @param toScreenId     Destination screen ID.
     * @param targetElement  Optional specific element to click on the final destination screen.
     * @param message        Human-readable diagnostic summary (logged and displayed in UI).
     */
    data class PathSearchResult(
        val found: Boolean,
        val pathString: String?,
        val steps: List<String>,
        val fromScreenId: String?,
        val toScreenId: String?,
        val targetElement: String?,
        val message: String
    ) {
        companion object {
            fun notFound(
                fromScreenId: String? = null,
                toScreenId: String? = null,
                message: String
            ) = PathSearchResult(
                found = false,
                pathString = null,
                steps = emptyList(),
                fromScreenId = fromScreenId,
                toScreenId = toScreenId,
                targetElement = null,
                message = message
            )

            fun found(
                pathString: String,
                steps: List<String>,
                fromScreenId: String,
                toScreenId: String,
                targetElement: String? = null,
                message: String = "Path resolved successfully in local graph"
            ) = PathSearchResult(
                found = true,
                pathString = pathString,
                steps = steps,
                fromScreenId = fromScreenId,
                toScreenId = toScreenId,
                targetElement = targetElement,
                message = message
            )
        }
    }

    /**
     * Primary entry point for local graph path search.
     *
     * @param context            Android context to access SQLite [NavGraphDatabase].
     * @param targetApp          Target app name (e.g. "Settings", "WhatsApp") or package name.
     * @param destinationScreen  Target screen title or feature (e.g. "Bluetooth", "Profile").
     * @param exactTask          Optional specific task (e.g. "Enable Bluetooth toggle").
     * @param currentScreenId    Optional override for current screen ID. If null, queries [GraphStateMachine].
     */
    fun findPath(
        context: Context,
        targetApp: String,
        destinationScreen: String,
        exactTask: String? = null,
        currentScreenId: String? = null
    ): PathSearchResult {
        val db = NavGraphDatabase.getInstance(context)

        // Resolve package name — try hardcoded map first, then DB package scan
        val knownPackage = GraphStateMachine.getPackageNameForApp(targetApp)
            ?: targetApp.trim().takeIf { it.contains('.') }

        // Load screens for the known package
        var packageName = knownPackage ?: targetApp.trim()
        var screens = if (knownPackage != null) db.getScreens(knownPackage) else emptyList()

        // If no screens found under the hardcoded package, search the DB for any installed
        // package whose name contains the app name (handles modded / OEM variants, e.g.
        // "app.morphe.android.youtube" for YouTube, "com.coloros.contacts" for Contacts, etc.)
        if (screens.isEmpty()) {
            val allPackages = db.getAllPackages()
            val matchingPkg = allPackages.firstOrNull { pkg ->
                pkg.contains(targetApp, ignoreCase = true) ||
                pkg.substringAfterLast('.').contains(targetApp, ignoreCase = true)
            }
            if (matchingPkg != null) {
                packageName = matchingPkg
                screens = db.getScreens(matchingPkg)
                Log.d(TAG, "Package fallback: '$targetApp' → found '$matchingPkg' in DB (${screens.size} screens)")
            }
        }

        val transitions = db.getTransitions(packageName)

        val result = findPathInGraph(
            screens = screens,
            transitions = transitions,
            currentScreenId = currentScreenId ?: GraphStateMachine.currentScreenId(packageName),
            destinationScreen = destinationScreen,
            exactTask = exactTask,
            appDisplayName = targetApp,
            packageName = packageName
        )

        // Standardized [PathFinder] logging
        if (result.found) {
            Log.i("[PathFinder]", "Tier 1 Local DB: Path Found -> ${result.pathString}")
        } else {
            Log.i("[PathFinder]", "Tier 1 Local DB: Path Miss -> ${result.message}")
        }

        return result
    }

    /**
     * Pure graph search algorithm decoupled from Android SQLite dependencies.
     * Can be tested directly on JVM with in-memory records.
     */
    fun findPathInGraph(
        screens: List<NavGraphDatabase.ScreenRecord>,
        transitions: List<NavGraphDatabase.TransitionRecord>,
        currentScreenId: String?,
        destinationScreen: String,
        exactTask: String?,
        appDisplayName: String? = null,
        packageName: String? = null
    ): PathSearchResult {
        if (screens.isEmpty()) {
            return PathSearchResult.notFound(
                message = "No recorded screens in local graph for app '${appDisplayName ?: packageName ?: "unknown"}'"
            )
        }

        // ── 1. Target Screen & Element Resolution ─────────────────────────────
        val targetResolution = resolveTarget(screens, destinationScreen, exactTask)
            ?: return PathSearchResult.notFound(
                message = "Destination '$destinationScreen' could not be mapped to any recorded screen or element"
            )

        val targetScreen = targetResolution.screen
        val targetElement = targetResolution.elementName

        // ── 2. Start Screen — always the app's root/home, never current screen ──
        // We intentionally ignore currentScreenId so the path always reads as a
        // full tutorial: "From WhatsApp home → More options → Settings → Chats → Chat backup"
        val startScreen = resolveRootScreen(screens, transitions, appDisplayName)
            ?: return PathSearchResult.notFound(
                toScreenId = targetScreen.id,
                message = "Could not identify the app home screen for '${appDisplayName ?: packageName ?: "unknown"}'"
            )

        // ── 3. If root IS the target screen, just return the element ─────────
        if (startScreen.id == targetScreen.id) {
            val steps = mutableListOf<String>()
            if (!targetElement.isNullOrEmpty()) steps.add(targetElement)
            else steps.add(targetScreen.screenTitle)
            return PathSearchResult.found(
                pathString = steps.joinToString(" -> "),
                steps = steps,
                fromScreenId = startScreen.id,
                toScreenId = targetScreen.id,
                targetElement = targetElement,
                message = "Target is on the app home screen"
            )
        }

        // ── 4. Graph Construction & Shortest Path (Dijkstra) ──────────────────
        val outgoing = mutableMapOf<String, MutableList<NavGraphDatabase.TransitionRecord>>()
        for (t in transitions) {
            outgoing.getOrPut(t.fromScreenId) { mutableListOf() }.add(t)
        }

        val shortestPathEdges = dijkstra(
            startId = startScreen.id,
            targetId = targetScreen.id,
            outgoing = outgoing
        )

        if (shortestPathEdges == null) {
            return PathSearchResult.notFound(
                fromScreenId = startScreen.id,
                toScreenId = targetScreen.id,
                message = "No navigable path from '${startScreen.screenTitle}' to '${targetScreen.screenTitle}' in local graph"
            )
        }

        // ── 5. Reconstruct Step Sequence ──────────────────────────────────────
        val steps = mutableListOf<String>()
        for (edge in shortestPathEdges) {
            if (edge.actionLabel.isNotBlank() && edge.actionLabel != "BACK") {
                steps.add(edge.actionLabel)
            } else {
                // Fallback to screen title derived from target
                val toTitle = edge.toScreenId.substringAfter("::")
                steps.add(toTitle)
            }
        }

        // If target element is present and not redundant, append as final action
        if (!targetElement.isNullOrEmpty() && (steps.isEmpty() || !steps.last().equals(targetElement, ignoreCase = true))) {
            steps.add(targetElement)
        }

        // Human-readable path representation
        val pathString = steps.joinToString(" -> ")

        return PathSearchResult.found(
            pathString = pathString,
            steps = steps,
            fromScreenId = startScreen.id,
            toScreenId = targetScreen.id,
            targetElement = targetElement,
            message = "Path found in local graph (${steps.size} steps)"
        )
    }

    // ── Internal Helpers ──────────────────────────────────────────────────────

    private data class TargetCandidate(
        val screen: NavGraphDatabase.ScreenRecord,
        val elementName: String? = null,
        val score: Int
    )

    private val OVERLAY_SCREEN_TITLES = setOf(
        // Our own overlay bubble
        "floating assistant", "floatingassistant",
        // Android system task-switcher / recents screen (captured under the last-active app's package)
        "no recent tasks",
        // Generic unnamed container — root_class FrameLayout with no real title
        "frame layout",
        // Any AD dialog captured as a screen
        "ad"
    )

    /**
     * Resolves the target screen and optional target element from destination / task.
     */
    private fun resolveTarget(
        screens: List<NavGraphDatabase.ScreenRecord>,
        destinationScreen: String,
        exactTask: String?
    ): TargetCandidate? {
        val candidates = mutableListOf<TargetCandidate>()
        val destClean = destinationScreen.trim()
        val taskClean = exactTask?.trim().orEmpty()
        val validScreens = screens.filter { it.screenTitle.lowercase().trim() !in OVERLAY_SCREEN_TITLES }

        for (screen in validScreens) {
            val title = screen.screenTitle.trim()

            // A. Exact title match (highest confidence)
            if (title.equals(destClean, ignoreCase = true)) {
                candidates.add(TargetCandidate(screen, elementName = null, score = 100))
                continue
            }

            // B. Substring title match
            if (title.contains(destClean, ignoreCase = true) || destClean.contains(title, ignoreCase = true)) {
                candidates.add(TargetCandidate(screen, elementName = null, score = 70))
            }

            // C. Search inside elements_json for matching element
            val matchedElement = findMatchingElement(screen.elementsJson, destClean, taskClean)
            if (matchedElement != null) {
                val score = if (matchedElement.equals(destClean, ignoreCase = true) ||
                    matchedElement.equals(taskClean, ignoreCase = true)
                ) 85 else 60
                candidates.add(TargetCandidate(screen, elementName = matchedElement, score = score))
            }
        }

        return candidates.maxByOrNull { it.score }
    }

    /**
     * Checks if any clean element in [elementsJson] matches the target destination or task.
     */
    private fun findMatchingElement(
        elementsJson: String,
        destClean: String,
        taskClean: String
    ): String? {
        if (elementsJson.isBlank()) return null
        return try {
            val arr = JSONArray(elementsJson)
            var bestMatch: String? = null
            var bestScore = 0

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue

                // 1. Exact match with destination or task
                if (name.equals(destClean, ignoreCase = true) ||
                    (taskClean.isNotEmpty() && name.equals(taskClean, ignoreCase = true))
                ) {
                    return name
                }

                // 2. Substring match
                if (destClean.length >= 3 && (name.contains(destClean, ignoreCase = true) || destClean.contains(name, ignoreCase = true))) {
                    if (bestScore < 30) {
                        bestScore = 30
                        bestMatch = name
                    }
                } else if (taskClean.length >= 3 && (name.contains(taskClean, ignoreCase = true) || taskClean.contains(name, ignoreCase = true))) {
                    if (bestScore < 20) {
                        bestScore = 20
                        bestMatch = name
                    }
                }
            }
            bestMatch
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves the starting screen. Prefers the active screen if given, otherwise finds
     * the root / entry screen for the package.
     */
    private fun resolveStartScreen(
        screens: List<NavGraphDatabase.ScreenRecord>,
        transitions: List<NavGraphDatabase.TransitionRecord>,
        currentScreenId: String?,
        appDisplayName: String? = null
    ): NavGraphDatabase.ScreenRecord? {
        val validScreens = screens.filter { it.screenTitle.lowercase().trim() !in OVERLAY_SCREEN_TITLES }
        if (validScreens.isEmpty()) return null

        // Priority 1: Current active screen matches an existing record (if not overlay)
        if (!currentScreenId.isNullOrBlank()) {
            validScreens.firstOrNull { it.id == currentScreenId }?.let { return it }
        }

        // Priority 2: Screen title matches the app display name (e.g. "WhatsApp", "Settings")
        if (!appDisplayName.isNullOrBlank()) {
            validScreens.firstOrNull { it.screenTitle.equals(appDisplayName, ignoreCase = true) }?.let { return it }
        }

        // Priority 3: Look for root screen (common entry points like "Settings", "Home", "WhatsApp")
        val knownEntryTitles = setOf("settings", "home", "whatsapp", "main", "conversations", "dialer", "camera")
        validScreens.firstOrNull { it.screenTitle.lowercase() in knownEntryTitles }?.let { return it }

        // Priority 4: Screen with 0 in-degree in recorded transitions (true graph entry node)
        val destinations = transitions.map { it.toScreenId }.toSet()
        validScreens.firstOrNull { it.id !in destinations }?.let { return it }

        // Priority 5: Highest visit count / earliest firstSeen
        return validScreens.maxByOrNull { it.visitCount } ?: validScreens.minByOrNull { it.firstSeen }
    }

    /**
     * Resolves the app's root / home screen unconditionally — ignoring the active stack.
     * Used to compute a full tutorial path even when the user is already deep in the app.
     */
    private fun resolveRootScreen(
        screens: List<NavGraphDatabase.ScreenRecord>,
        transitions: List<NavGraphDatabase.TransitionRecord>,
        appDisplayName: String? = null
    ): NavGraphDatabase.ScreenRecord? {
        val validScreens = screens.filter { it.screenTitle.lowercase().trim() !in OVERLAY_SCREEN_TITLES }
        if (validScreens.isEmpty()) return null

        // 1. Screen titled exactly like the app name (e.g. "WhatsApp")
        if (!appDisplayName.isNullOrBlank()) {
            validScreens.firstOrNull { it.screenTitle.equals(appDisplayName, ignoreCase = true) }?.let { return it }
        }

        // 2. Known entry titles
        val knownEntryTitles = setOf("settings", "home", "whatsapp", "main", "conversations", "dialer", "camera")
        validScreens.firstOrNull { it.screenTitle.lowercase() in knownEntryTitles }?.let { return it }

        // 3. Zero in-degree (true graph root)
        val destinations = transitions.map { it.toScreenId }.toSet()
        validScreens.firstOrNull { it.id !in destinations }?.let { return it }

        return null
    }

    private data class DijkstraNode(
        val screenId: String,
        val cost: Double
    ) : Comparable<DijkstraNode> {
        override fun compareTo(other: DijkstraNode): Int = cost.compareTo(other.cost)
    }

    /**
     * Runs Dijkstra's shortest path algorithm over the recorded transitions.
     * Weights account for traversal count: frequently chosen edges have lower relative cost.
     */
    private fun dijkstra(
        startId: String,
        targetId: String,
        outgoing: Map<String, List<NavGraphDatabase.TransitionRecord>>
    ): List<NavGraphDatabase.TransitionRecord>? {
        val dist = mutableMapOf<String, Double>()
        val prev = mutableMapOf<String, Pair<String, NavGraphDatabase.TransitionRecord>>()
        val visited = mutableSetOf<String>()
        val pq = PriorityQueue<DijkstraNode>()

        dist[startId] = 0.0
        pq.add(DijkstraNode(startId, 0.0))

        while (pq.isNotEmpty()) {
            val (u, currentDist) = pq.poll() ?: break

            if (u == targetId) break
            if (visited.contains(u)) continue
            visited.add(u)

            val edges = outgoing[u] ?: emptyList()
            for (edge in edges) {
                val v = edge.toScreenId
                if (visited.contains(v)) continue

                // Base edge weight: lower cost for edges traversed more often
                val traversalBonus = 1.0 / (1.0 + edge.traversalCount * 0.1)
                val edgeCost = edge.weight * traversalBonus
                val newDist = currentDist + edgeCost

                if (newDist < (dist[v] ?: Double.MAX_VALUE)) {
                    dist[v] = newDist
                    prev[v] = Pair(u, edge)
                    pq.add(DijkstraNode(v, newDist))
                }
            }
        }

        if (!prev.containsKey(targetId) && startId != targetId) {
            return null
        }

        // Reconstruct path
        val path = mutableListOf<NavGraphDatabase.TransitionRecord>()
        var curr = targetId
        while (curr != startId) {
            val step = prev[curr] ?: return null
            path.add(step.second)
            curr = step.first
        }
        path.reverse()
        return path
    }
}
