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

        // ── 1. Target Screen Candidates ─────────────────────────────
        val targetCandidates = resolveTarget(screens, destinationScreen)
        if (targetCandidates.isEmpty()) {
            return PathSearchResult.notFound(
                message = "Destination '$destinationScreen' could not be mapped to any recorded screen or element"
            )
        }

        // ── 2. Start Screen — always the app's root/home, never current screen ──
        val startScreen = resolveRootScreen(screens, transitions, appDisplayName)
            ?: return PathSearchResult.notFound(
                toScreenId = targetCandidates.first().screen.id,
                message = "Could not identify the app home screen for '${appDisplayName ?: packageName ?: "unknown"}'"
            )

        // ── 3. Graph Construction ──────────────────
        val outgoing = mutableMapOf<String, MutableList<NavGraphDatabase.TransitionRecord>>()
        for (t in transitions) {
            outgoing.getOrPut(t.fromScreenId) { mutableListOf() }.add(t)
        }

        // ── 4. Find Best Reachable Target ──────────────────
        var bestMissMessage: String? = null
        var bestMissTargetId: String? = null

        for (candidate in targetCandidates) {
            val targetScreen = candidate.screen
            val targetElement = candidate.elementName

            // If root IS the target screen, just return the element
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

            // Otherwise run Dijkstra
            val shortestPathEdges = dijkstra(
                startId = startScreen.id,
                targetId = targetScreen.id,
                outgoing = outgoing
            )

            if (shortestPathEdges != null) {
                // Reconstruct Step Sequence
                val steps = mutableListOf<String>()
                for (edge in shortestPathEdges) {
                    if (edge.actionLabel.isNotBlank() && edge.actionLabel != "BACK") {
                        steps.add(edge.actionLabel)
                    } else {
                        val toTitle = edge.toScreenId.substringAfter("::")
                        steps.add(toTitle)
                    }
                }
                
                // If target element is present and not redundant, append as final action
                if (!targetElement.isNullOrEmpty() && (steps.isEmpty() || !steps.last().equals(targetElement, ignoreCase = true))) {
                    steps.add(targetElement)
                }

                val pathString = steps.joinToString(" -> ")

                return PathSearchResult.found(
                    pathString = pathString,
                    steps = steps,
                    fromScreenId = startScreen.id,
                    toScreenId = targetScreen.id,
                    targetElement = targetElement,
                    message = "Path found in local graph (${steps.size} steps)"
                )
            } else {
                // Track miss for the highest scoring candidate (first one)
                if (bestMissMessage == null) {
                    bestMissMessage = "No navigable path from '${startScreen.screenTitle}' to '${targetScreen.screenTitle}' in local graph"
                    bestMissTargetId = targetScreen.id
                }
            }
        }

        // If we reach here, NO target candidate was reachable
        return PathSearchResult.notFound(
            fromScreenId = startScreen.id,
            toScreenId = bestMissTargetId,
            message = bestMissMessage ?: "No reachable target found"
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

    private data class MatchResult(
        val matched: Boolean,
        val score: Int
    )

    private fun normalizeText(text: String): String {
        return text
            .lowercase()
            .replace("&", " and ")
            .replace("-", " ")
            .replace("_", " ")
            .replace("/", " ")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun tokenize(text: String): List<String> {
        return normalizeText(text)
            .split(" ")
            .filter { it.length >= 2 }
    }

    private fun levenshteinDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length

        val previous = IntArray(b.length + 1) { it }
        val current = IntArray(b.length + 1)

        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost
                )
            }
            for (j in previous.indices) {
                previous[j] = current[j]
            }
        }
        return previous[b.length]
    }

    private fun calculateMatchScore(candidate: String, query: String): MatchResult {
        val candidateNorm = normalizeText(candidate)
        val queryNorm = normalizeText(query)

        if (candidateNorm.isBlank() || queryNorm.isBlank()) {
            return MatchResult(false, 0)
        }

        // 1. Exact match
        if (candidateNorm == queryNorm) {
            return MatchResult(true, 100)
        }

        val candidateTokens = tokenize(candidateNorm)
        val queryTokens = tokenize(queryNorm)

        if (candidateTokens.isEmpty() || queryTokens.isEmpty()) {
            return MatchResult(false, 0)
        }

        var score = 0

        // 2. Exact token matching
        val commonTokens = queryTokens.intersect(candidateTokens.toSet())
        if (commonTokens.isNotEmpty()) {
            val tokenCoverage = commonTokens.size.toDouble() / queryTokens.size
            score += (tokenCoverage * 55).toInt()
        }

        // 3. Whole-word containment
        val candidateWordPattern = Regex("(^|\\s)${Regex.escape(queryNorm)}(\\s|$)")
        val queryWordPattern = Regex("(^|\\s)${Regex.escape(candidateNorm)}(\\s|$)")
        if (candidateWordPattern.containsMatchIn(candidateNorm) || queryWordPattern.containsMatchIn(queryNorm)) {
            score = maxOf(score, 82)
        }

        // 4. Phrase containment
        if (candidateNorm.contains(queryNorm) || queryNorm.contains(candidateNorm)) {
            val shorterTokens = minOf(candidateTokens.size, queryTokens.size)
            val longerTokens = maxOf(candidateTokens.size, queryTokens.size)
            if (shorterTokens > 0) {
                val tokenRatio = shorterTokens.toDouble() / longerTokens
                if (tokenRatio >= 0.5 || commonTokens.isNotEmpty()) {
                    score = maxOf(score, 78)
                }
            }
        }

        // 5. Prefix matching
        if (candidateNorm.startsWith(queryNorm) || queryNorm.startsWith(candidateNorm)) {
            if (queryNorm.length >= 4 && candidateNorm.length >= 4) {
                score = maxOf(score, 75)
            }
        }

        // 6. Fuzzy token matching
        var fuzzyTokenFound = false
        for (queryToken in queryTokens) {
            for (candidateToken in candidateTokens) {
                if (queryToken.length < 4 || candidateToken.length < 4) continue
                val distance = levenshteinDistance(queryToken, candidateToken)
                val maxLength = maxOf(queryToken.length, candidateToken.length)
                val similarity = 1.0 - (distance.toDouble() / maxLength)
                if (similarity >= 0.80) {
                    fuzzyTokenFound = true
                    break
                }
            }
            if (fuzzyTokenFound) break
        }
        if (fuzzyTokenFound) {
            score = maxOf(score, 65)
        }

        return MatchResult(matched = score >= 70, score = score)
    }

    private fun resolveTarget(
        screens: List<NavGraphDatabase.ScreenRecord>,
        destinationScreen: String
    ): List<TargetCandidate> {
        val candidates = mutableListOf<TargetCandidate>()
        val destClean = destinationScreen.trim()
        val validScreens = screens.filter { it.screenTitle.lowercase().trim() !in OVERLAY_SCREEN_TITLES }

        for (screen in validScreens) {
            // 1. Screen title matching
            val screenMatch = calculateMatchScore(screen.screenTitle, destClean)
            if (screenMatch.matched) {
                candidates.add(TargetCandidate(screen, null, screenMatch.score))
            }

            // 2. Element matching
            val matchedElement = findBestMatchingElement(screen.elementsJson, destClean)
            if (matchedElement != null) {
                candidates.add(TargetCandidate(screen, matchedElement.name, matchedElement.score))
            }

            // 3. WhatsApp Settings Heuristic
            if (destClean.equals("Settings", ignoreCase = true) &&
                screen.rootClass.contains("Settings", ignoreCase = true) &&
                screen.elementsJson.contains("\"Chats\"", ignoreCase = true) &&
                screen.elementsJson.contains("\"Privacy\"", ignoreCase = true)
            ) {
                candidates.add(TargetCandidate(screen, null, 95))
            }
        }

        val sorted = candidates.filter { it.score >= 70 }.sortedByDescending { it.score }
        
        if (sorted.isNotEmpty()) {
            Log.d(TAG, "resolveTarget('$destClean'): ${sorted.size} trustworthy candidate(s) — " +
                sorted.joinToString { "'${it.elementName ?: it.screen.screenTitle}'(score=${it.score})" })
        } else {
            Log.d(TAG, "resolveTarget('$destClean'): 0 trustworthy candidates")
        }
        return sorted
    }

    private data class ElementMatch(val name: String, val score: Int)

    private fun findBestMatchingElement(elementsJson: String, destination: String): ElementMatch? {
        if (elementsJson.isBlank()) return null
        return try {
            val arr = JSONArray(elementsJson)
            var bestMatch: ElementMatch? = null

            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isEmpty()) continue

                val result = calculateMatchScore(name, destination)
                if (!result.matched) continue

                if (bestMatch == null || result.score > bestMatch.score) {
                    bestMatch = ElementMatch(name, result.score)
                }
            }
            bestMatch
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse elements_json", e)
            null
        }
    }

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
