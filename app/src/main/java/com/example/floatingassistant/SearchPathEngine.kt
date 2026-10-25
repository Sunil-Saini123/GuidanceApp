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
    // [PERMANENT FIX] Do not remove this! Multi-path data model for candidate routes suggested by local graph.
    data class DiscoveredPath(
        val pathString: String,
        val steps: List<String>,
        val cost: Double,
        val startScreen: String,
        val destinationScreen: String,
        val targetElement: String?,
        val isShortest: Boolean = false,
        val score: Int = 0,
        val description: String = ""
    )

    data class PathSearchResult(
        val found: Boolean,
        val pathString: String?,
        val steps: List<String>,
        val fromScreenId: String?,
        val toScreenId: String?,
        val targetElement: String?,
        val message: String,
        val allDiscoveredPaths: List<DiscoveredPath> = emptyList()
    ) {
        companion object {
            fun notFound(
                fromScreenId: String? = null,
                toScreenId: String? = null,
                message: String,
                allDiscoveredPaths: List<DiscoveredPath> = emptyList()
            ) = PathSearchResult(
                found = false,
                pathString = null,
                steps = emptyList(),
                fromScreenId = fromScreenId,
                toScreenId = toScreenId,
                targetElement = null,
                message = message,
                allDiscoveredPaths = allDiscoveredPaths
            )

            fun found(
                pathString: String,
                steps: List<String>,
                fromScreenId: String,
                toScreenId: String,
                targetElement: String? = null,
                message: String = "Path resolved successfully in local graph",
                allDiscoveredPaths: List<DiscoveredPath> = emptyList()
            ) = PathSearchResult(
                found = true,
                pathString = pathString,
                steps = steps,
                fromScreenId = fromScreenId,
                toScreenId = toScreenId,
                targetElement = targetElement,
                message = message,
                allDiscoveredPaths = allDiscoveredPaths
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

        // [PERMANENT FIX] Do not remove this! Load connected screens and transitions across OEM sub-packages
        // (e.g. com.oplus.wirelesssettings, com.oplus.battery launched from com.android.settings).
        var packageName = knownPackage ?: targetApp.trim()
        var screens = if (knownPackage != null) db.getConnectedScreens(knownPackage) else emptyList()

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
                screens = db.getConnectedScreens(matchingPkg)
                Log.d(TAG, "Package fallback: '$targetApp' → found '$matchingPkg' in DB (${screens.size} screens)")
            }
        }

        val transitions = db.getConnectedTransitions(packageName)

        // ── Heuristic Shortcuts for Generic Intents ──
        // If the graph is sparse, Dijkstra might route through convoluted multi-step paths 
        // just to find a common element like "Search" that was missed on the Home screen.
        // We intercept generic intents here and return a direct 1-step path.
        val destNorm = destinationScreen.trim().lowercase()
        if (destNorm == "search_page" || destNorm == "search") {
            return PathSearchResult.found(
                pathString = "Search",
                steps = listOf("Search"),
                fromScreenId = "heuristic",
                toScreenId = "heuristic",
                targetElement = "Search",
                message = "Heuristic shortcut for generic search intent"
            )
        }

        // [PERMANENT FIX] Do not remove this! Live Foreground App Resolution for Dijkstra Start Screen.
        // If the user initiates navigation from outside the target app (e.g. from GuidanceApp panel or Launcher),
        // we must NEVER assume the user is still on a deep background sub-page from a previous session.
        // In that case, activeScreenId must be null so Dijkstra starts from the app's canonical root home screen.
        val globalLastPkg = GraphStateMachine.getGlobalLastPackageName()
        val isTargetAppInForeground = globalLastPkg != null && (
            globalLastPkg.equals(packageName, true) ||
            screens.any { it.packageName.equals(globalLastPkg, true) }
        )

        val activeScreenId = if (currentScreenId != null) {
            currentScreenId
        } else if (isTargetAppInForeground) {
            GraphStateMachine.currentScreenId(packageName) ?: GraphStateMachine.getGlobalLastScreenId()
        } else {
            null // User is outside the app -> start from the app root screen!
        }

        val result = findPathInGraph(
            screens = screens,
            transitions = transitions,
            currentScreenId = activeScreenId,
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
        // [PERMANENT FIX] Do not remove this! Resolve targets across both destinationScreen and exactTask.
        // Strips generic suffixes and action prefixes, prioritizing specific exactTask element targets.
        val targetCandidates = resolveTarget(screens, destinationScreen, exactTask)
        if (targetCandidates.isEmpty()) {
            return PathSearchResult.notFound(
                message = "Destination '$destinationScreen' (task='${exactTask ?: ""}') could not be mapped to any recorded screen or element"
            )
        }

        // ── 2. Start Screen Resolution ──────────────────────────────────────────
        // [PERMANENT FIX] Do not remove this! Prioritize the user's actual current screen in the app.
        // If a path from the current screen exists, Dijkstra uses it for instant 1-2 step guidance.
        // If unreachable from current screen (dead-end or backward route), gracefully fall back to the app root screen.
        val resolvedStart = resolveStartScreen(screens, transitions, currentScreenId, appDisplayName)
        val rootScreen = resolveRootScreen(screens, transitions, appDisplayName)
        val primaryStart = resolvedStart ?: rootScreen
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
        // Try starting from current screen first; if unreachable, fall back to root screen.
        val searchStarts = if (resolvedStart != null && rootScreen != null && resolvedStart.id != rootScreen.id) {
            listOf(resolvedStart to "current screen", rootScreen to "app root screen")
        } else {
            listOf(primaryStart to "app home screen")
        }

        var bestMissMessage: String? = null
        var bestMissTargetId: String? = null

        // [PERMANENT FIX] Do not remove this! Multi-Path Collection & Evaluation.
        // Instead of halting on the very first path, evaluates all candidates and start screens,
        // collecting all navigable routes into allDiscoveredPaths and selecting the optimal shortest route.
        val allDiscoveredPaths = mutableListOf<DiscoveredPath>()
        var bestFoundResult: PathSearchResult? = null

        for ((startScreen, startDescription) in searchStarts) {
            for (candidate in targetCandidates) {
                val targetScreen = candidate.screen
                val targetElement = candidate.elementName

                val isRoot = startScreen.id == rootScreen?.id ||
                             startScreen.screenTitle.equals(appDisplayName, ignoreCase = true) ||
                             setOf("chats", "settings", "home", "main", "conversations").contains(startScreen.screenTitle.lowercase().trim())
                val hasSpecificTask = !exactTask.isNullOrBlank()
                val isSubpageDestination = destinationScreen.isNotBlank() &&
                                           !destinationScreen.equals(startScreen.screenTitle, ignoreCase = true) &&
                                           !destinationScreen.equals(appDisplayName, ignoreCase = true) &&
                                           !destinationScreen.equals("home", ignoreCase = true) &&
                                           !destinationScreen.equals("main", ignoreCase = true)
                val cannotShortCircuitOnRoot = isRoot && (hasSpecificTask || isSubpageDestination)

                if (cannotShortCircuitOnRoot && targetScreen.id == startScreen.id) {
                    continue
                }

                if (startScreen.id == targetScreen.id) {
                    val steps = mutableListOf<String>()
                    if (!targetElement.isNullOrEmpty()) steps.add(targetElement)
                    else steps.add(targetScreen.screenTitle)
                    val pStr = steps.joinToString(" -> ")
                    val disc = DiscoveredPath(
                        pathString = pStr,
                        steps = steps,
                        cost = 0.0,
                        startScreen = startScreen.screenTitle,
                        destinationScreen = targetScreen.screenTitle,
                        targetElement = targetElement,
                        score = candidate.score,
                        description = "Target is on $startDescription"
                    )
                    if (allDiscoveredPaths.none { it.pathString == pStr }) {
                        allDiscoveredPaths.add(disc)
                    }
                    if (bestFoundResult == null) {
                        bestFoundResult = PathSearchResult.found(
                            pathString = pStr,
                            steps = steps,
                            fromScreenId = startScreen.id,
                            toScreenId = targetScreen.id,
                            targetElement = targetElement,
                            message = "Target is on the $startDescription"
                        )
                    }
                    continue
                }

                val shortestPathEdges = dijkstra(
                    startId = startScreen.id,
                    targetId = targetScreen.id,
                    outgoing = outgoing
                )

                if (shortestPathEdges != null) {
                    val steps = mutableListOf<String>()
                    var pathCost = 0.0
                    for (edge in shortestPathEdges) {
                        pathCost += edge.weight
                        if (edge.actionLabel.equals("BACK", ignoreCase = true)) {
                            steps.add("BACK")
                        } else if (edge.actionLabel.isNotBlank()) {
                            steps.add(edge.actionLabel)
                        } else {
                            val toTitle = edge.toScreenId.substringAfter("::")
                            steps.add(toTitle)
                        }
                    }
                    
                    if (!targetElement.isNullOrEmpty() && (steps.isEmpty() || !steps.last().equals(targetElement, ignoreCase = true))) {
                        steps.add(targetElement)
                    }

                    val pathString = steps.joinToString(" -> ")
                    val disc = DiscoveredPath(
                        pathString = pathString,
                        steps = steps,
                        cost = pathCost,
                        startScreen = startScreen.screenTitle,
                        destinationScreen = targetScreen.screenTitle,
                        targetElement = targetElement,
                        score = candidate.score,
                        description = "${steps.size} steps from $startDescription"
                    )
                    if (allDiscoveredPaths.none { it.pathString == pathString }) {
                        allDiscoveredPaths.add(disc)
                    }

                    if (bestFoundResult == null) {
                        bestFoundResult = PathSearchResult.found(
                            pathString = pathString,
                            steps = steps,
                            fromScreenId = startScreen.id,
                            toScreenId = targetScreen.id,
                            targetElement = targetElement,
                            message = "Path found in local graph (${steps.size} steps from $startDescription)"
                        )
                    }
                } else {
                    if (bestMissMessage == null) {
                        bestMissMessage = "No navigable path from '${startScreen.screenTitle}' to '${targetScreen.screenTitle}' in local graph"
                        bestMissTargetId = targetScreen.id
                    }
                }
            }
        }

        if (bestFoundResult != null) {
            val sorted = allDiscoveredPaths.sortedWith(compareBy({ it.steps.size }, { it.cost }))
            val marked = sorted.mapIndexed { idx, p ->
                if (idx == 0 || p.pathString == bestFoundResult.pathString) p.copy(isShortest = true) else p
            }
            return bestFoundResult.copy(allDiscoveredPaths = marked)
        }

        return PathSearchResult.notFound(
            fromScreenId = primaryStart.id,
            toScreenId = bestMissTargetId,
            message = bestMissMessage ?: "No reachable target found",
            allDiscoveredPaths = allDiscoveredPaths
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
        "ad", "adanimationview",
        // Dialog backdrop dismissals
        "touch outside", "touch_outside", "touch outside to dismiss", "scrim",
        // Transient tooltips & focus inputs
        "tap to paste", "search box", "floa", "no sim cardnot set", "no sim card‎not set", "📌"
    )

    private data class MatchResult(
        val matched: Boolean,
        val score: Int
    )

    private fun normalizeText(text: String): String {
        return text
            .lowercase()
            // [PERMANENT FIX] Do not remove this! Normalizes typographic Unicode hyphens and dashes to spaces
            .replace(Regex("[\u2010\u2011\u2012\u2013\u2014\u2015\u2212]"), " ")
            .replace(Regex("[\u00A0\u202F]"), " ")
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

        // [PERMANENT FIX] Do not remove this! Ignore Generic Navigation Controls as Target Elements.
        // Common navigation controls ("Back", "Cancel", "Close", "OK", "Done") must never be matched
        // as user targets for tasks like "backup" or "chats".
        val ignoredTargetControls = setOf("back", "cancel", "close", "done", "ok", "navigate up")
        if (candidateNorm in ignoredTargetControls && queryNorm !in ignoredTargetControls) {
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
            if (candidateTokens.size == 1 && queryTokens.size > 1) {
                score = maxOf(score, 60)
            } else {
                score = maxOf(score, 82)
            }
        }

        // 4. Phrase containment
        if (candidateNorm.contains(queryNorm) || queryNorm.contains(candidateNorm)) {
            if (candidateTokens.size == 1 && queryTokens.size > 1) {
                score = maxOf(score, 60)
            } else {
                score = maxOf(score, 78)
            }
        }

        // 4b. Token ratio matching
        if (commonTokens.isNotEmpty()) {
            val queryMatchRatio = commonTokens.size.toDouble() / queryTokens.size
            val candidateMatchRatio = commonTokens.size.toDouble() / candidateTokens.size
            
            // If the majority of the query matches the candidate, it's a good match
            if (queryMatchRatio >= 0.6) {
                if (candidateTokens.size == 1 && queryTokens.size > 1) {
                    score = maxOf(score, 60) // Too generic, keep score low
                } else {
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

    /**
     * [PERMANENT FIX] Do not remove this! Normalizes intent labels into natural search queries.
     * Strips snake_case underscores, generic UI suffixes ("_settings", "_page", "_screen", "_tab", "_menu"),
     * and imperative action prefixes ("set_", "change_", "toggle_", "update_", "open_", "view_").
     * e.g. "display_settings" -> "display", "set_refresh_rate" -> "refresh rate".
     */
    fun cleanIntentQuery(raw: String): String {
        if (raw.isBlank()) return ""
        val unUnderscore = raw.replace('_', ' ').trim()
        val strippedSuffix = unUnderscore.replace(
            Regex("(?i)\\s+(settings|page|screen|tab|menu|section)$"), ""
        ).trim()
        val strippedPrefix = strippedSuffix.replace(
            Regex("(?i)^(set|change|toggle|update|open|view|enable|disable|check)\\s+"), ""
        ).trim()
        return strippedPrefix.ifEmpty { unUnderscore }
    }

    /**
     * [PERMANENT FIX] Do not remove this!
     * Resolves target screens and elements from user intent.
     * Searches across both destinationScreen (end_page) and exactTask (task).
     * Normalizes queries by stripping snake_case underscores, generic suffixes (_settings, _page),
     * and imperative prefixes (set_, change_).
     * Prioritizes exactTask element matches (the deep specific action) over generic screen matches.
     */
    private fun resolveTarget(
        screens: List<NavGraphDatabase.ScreenRecord>,
        destinationScreen: String,
        exactTask: String? = null
    ): List<TargetCandidate> {
        val validScreens = screens.filter { it.screenTitle.lowercase().trim() !in OVERLAY_SCREEN_TITLES }

        fun collectCandidatesForQueries(queries: List<String>): List<TargetCandidate> {
            val list = mutableListOf<TargetCandidate>()
            for (q in queries) {
                for (screen in validScreens) {
                    // Match elements on this screen
                    val matchedElement = findBestMatchingElement(screen.elementsJson, q)
                    if (matchedElement != null) {
                        list.add(TargetCandidate(screen, matchedElement.name, matchedElement.score))
                    }

                    // Match screen title
                    val screenMatch = calculateMatchScore(screen.screenTitle, q)
                    if (screenMatch.matched) {
                        list.add(TargetCandidate(screen, null, screenMatch.score))
                    }

                    // WhatsApp Settings Heuristic
                    if (q.equals("Settings", ignoreCase = true) &&
                        screen.rootClass.contains("Settings", ignoreCase = true) &&
                        screen.elementsJson.contains("\"Chats\"", ignoreCase = true) &&
                        screen.elementsJson.contains("\"Privacy\"", ignoreCase = true)
                    ) {
                        list.add(TargetCandidate(screen, null, 95))
                    }
                }
            }
            return list
        }

        // 1. Task queries (specific element targets, e.g. "set_refresh_rate", "refresh rate")
        val taskCandidates = if (!exactTask.isNullOrBlank()) {
            val taskRaw = exactTask.trim()
            val taskClean = cleanIntentQuery(taskRaw)
            val tQueries = linkedSetOf(taskRaw, taskClean).filter { it.isNotBlank() }
            collectCandidatesForQueries(tQueries)
        } else {
            emptyList()
        }

        // 2. Destination screen queries (category pages, e.g. "display_settings", "display")
        val destRaw = destinationScreen.trim()
        val destClean = cleanIntentQuery(destRaw)
        val dQueries = linkedSetOf(destRaw, destClean).filter { it.isNotBlank() }
        val destCandidates = collectCandidatesForQueries(dQueries)

        // Prioritize: Task element matches first (user's specific action), then destination screen matches
        val combined = mutableListOf<TargetCandidate>()
        val filteredTaskCandidates = taskCandidates.filter { it.score >= 70 }.sortedByDescending { it.score }
        val filteredDestCandidates = destCandidates.filter { it.score >= 70 }.sortedByDescending { it.score }

        // [PERMANENT FIX] Do not remove this! Specific Task Integrity:
        // If the user specified a specific sub-task (e.g. "Screen refresh rate" or "refresh rate") that differs from the category screen,
        // and that element does NOT exist in the local SQLite graph (filteredTaskCandidates is empty),
        // do not let generic destCandidates falsely claim a Tier 1 hit and stop at the parent screen (e.g. Display & brightness).
        // Let it miss so Tier 2 (Cloud DB) and Tier 3 (Groq AI) provide the complete, verified path including the leaf setting!
        val normTask = cleanIntentQuery(exactTask ?: "").lowercase().trim()
        val normDest = destClean.lowercase().trim()
        val isSpecificSubTask = normTask.isNotBlank() &&
                normTask != normDest &&
                normTask !in setOf("open", "view", "show", "launch", "settings", "app") &&
                !normDest.contains(normTask) &&
                !normTask.contains(normDest)

        val hasElementMatch = filteredTaskCandidates.any { !it.elementName.isNullOrBlank() } ||
                              filteredDestCandidates.any { !it.elementName.isNullOrBlank() }

        if (isSpecificSubTask && !hasElementMatch) {
            Log.i(TAG, "resolveTarget: Specific task '$exactTask' (cleaned: '$normTask') not found in local graph elements for category '$destinationScreen'. Suppressing category-only hit so Tier 2/3 can find the full path.")
            return emptyList()
        }

        combined.addAll(filteredTaskCandidates)
        combined.addAll(filteredDestCandidates)

        // Deduplicate candidates by (screen.id, elementName)
        val seen = HashSet<String>()
        val unique = mutableListOf<TargetCandidate>()
        for (c in combined) {
            val key = "${c.screen.id}::${c.elementName ?: ""}"
            if (seen.add(key)) {
                unique.add(c)
            }
        }

        if (unique.isNotEmpty()) {
            Log.d(TAG, "resolveTarget('$destRaw', task='$exactTask'): ${unique.size} trustworthy candidate(s) — " +
                unique.joinToString { "'${it.elementName ?: it.screen.screenTitle}'(score=${it.score})" })
        } else {
            Log.d(TAG, "resolveTarget('$destRaw', task='$exactTask'): 0 trustworthy candidates")
        }
        return unique
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
            val currNorm = currentScreenId.trim().lowercase()
            validScreens.firstOrNull { 
                it.id.equals(currentScreenId, ignoreCase = true) ||
                it.id.lowercase().endsWith("::" + currNorm.substringAfter("::")) ||
                it.screenTitle.equals(currNorm.substringAfter("::"), ignoreCase = true)
            }?.let { return it }
        }

        // Priority 2: Screen title matches the app display name (e.g. "WhatsApp", "Settings")
        if (!appDisplayName.isNullOrBlank()) {
            validScreens.firstOrNull { it.screenTitle.equals(appDisplayName, ignoreCase = true) }?.let { return it }
        }

        // Priority 3: Look for root screen (common entry points like "Settings", "Home", "WhatsApp")
        val knownEntryTitles = setOf("settings", "home", "whatsapp", "chats", "main", "conversations", "dialer", "camera")
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
        val knownEntryTitles = setOf("settings", "home", "whatsapp", "chats", "main", "conversations", "dialer", "camera")
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
