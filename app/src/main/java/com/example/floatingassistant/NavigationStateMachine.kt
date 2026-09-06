package com.example.floatingassistant

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.floatingassistant.pathgenerator.GroqHealer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

object NavigationStateMachine {

    private const val TAG = "[NavigationEngine]"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val stateMachineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var hud: NavigationHudOverlay
    private lateinit var context: Context
    private lateinit var elementFinder: ElementFinderEngine

    @Volatile var lastKnownActivePackage: String = ""
    @Volatile var isNavigating: Boolean = false
        private set

    private var targetPackage: String = ""
    private var initialTask: String = ""
    private var pathSteps: MutableList<String> = mutableListOf()
    private var currentIndex: Int = 0
    private var prevStep: String? = null
    private var currStep: String = ""
    private var nextStep: String? = null
    private var lastCorrectStep: String? = null
    private var activeScreenName: String = ""
    @Volatile var currentTargetBounds: Rect? = null

    fun attachHud(overlay: NavigationHudOverlay, ctx: Context) {
        hud = overlay
        context = ctx.applicationContext
        elementFinder = ElementFinderEngine(context)
        Log.d(TAG, "HUD + Context attached to NavigationStateMachine")
    }

    fun startNavigation(resolvedPath: List<String>, pkgTarget: String, exactTask: String = "") {
        if (resolvedPath.isEmpty()) return
        postToMain {
            targetPackage = pkgTarget.trim().lowercase()
            initialTask = exactTask
            pathSteps = resolvedPath.toMutableList()
            lastCorrectStep = null
            isNavigating = true

            Log.i(TAG, "Navigation STARTED - ${pathSteps.size} steps toward '$targetPackage'")

            val activePkg = lastKnownActivePackage
            val alreadyIn = activePkg.isNotEmpty() && isTargetApp(activePkg, targetPackage, pathSteps.firstOrNull())
            val launcherPkg = getDefaultLauncherPackage()

            val startIdx = if (alreadyIn) {
                var idx = 0
                while (idx < pathSteps.size) {
                    val stepNorm = pathSteps[idx].trim().lowercase()
                    val isHomeStep = stepNorm == "home" || (launcherPkg != null && launcherPkg.contains(stepNorm))
                    val isAppRootStep = appRootFuzzyMatch(stepNorm, targetPackage)
                    if (!isHomeStep && !isAppRootStep) break
                    idx++
                }
                idx.coerceAtMost(pathSteps.size - 1)
            } else {
                0
            }

            currentIndex = startIdx
            prevStep = if (startIdx > 0) pathSteps[startIdx - 1] else null
            currStep = pathSteps[currentIndex]
            nextStep = pathSteps.getOrNull(currentIndex + 1)

            val ctxText = "Current: $currStep | Next: ${nextStep ?: "None"}"
            
            val isLauncherNow = launcherPkg != null && activePkg.startsWith(launcherPkg)
            val header = if (isLauncherNow) "ON HOME" else if (!alreadyIn) "WRONG APP" else "STEP ${currentIndex + 1} OF ${pathSteps.size}"
            val instruction = if (!alreadyIn) "Open ${targetPackage.replaceFirstChar { it.uppercase() }}" else "Look for '$currStep'"
            
            hud.updateHud(
                stepHeader = header,
                actionInstruction = instruction,
                contextText = ctxText,
                subTag = if (startIdx > 0) "fast-forwarded" else "start"
            )
            hud.show()
            triggerSearchPipeline(currStep, activePkg, activeScreenName)
        }
    }

    fun stopNavigation() {
        postToMain {
            if (!isNavigating) return@postToMain
            isNavigating = false
            targetPackage = ""
            pathSteps = mutableListOf()
            currentIndex = 0
            prevStep = null
            currStep = ""
            nextStep = null
            lastCorrectStep = null
            activeScreenName = ""
            hud.hide()
        }
    }
    
    fun stop() = stopNavigation()
    fun isRunning() = isNavigating

    fun onScreenChanged(activePackage: String, currentScreenName: String) {
        if (!isNavigating) return
        lastKnownActivePackage = activePackage

        postToMain {
            if (!isNavigating) return@postToMain
            
            val launcherPkg = getDefaultLauncherPackage()
            val isLauncherEvent = launcherPkg != null && activePackage.startsWith(launcherPkg)
            
            if (isLauncherEvent) {
                activeScreenName = "Home"
            } else {
                activeScreenName = currentScreenName
            }

            val currStepIsHome = currStep.trim().lowercase() == "home"
            val inTargetApp = isTargetApp(activePackage, targetPackage, currStep)

            if (currentIndex == 0 && inTargetApp && !isLauncherEvent) {
                var startIdx = 0
                while (startIdx < pathSteps.size) {
                    val stepNorm = pathSteps[startIdx].trim().lowercase()
                    val isHome = stepNorm == "home" || (launcherPkg != null && launcherPkg.contains(stepNorm))
                    val isAppRoot = appRootFuzzyMatch(stepNorm, targetPackage)
                    if (!isHome && !isAppRoot) break
                    startIdx++
                }
                startIdx = startIdx.coerceAtMost(pathSteps.size - 1)
                
                if (startIdx > currentIndex) {
                    currentIndex = startIdx
                    prevStep = if (startIdx > 0) pathSteps[startIdx - 1] else null
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    lastCorrectStep = prevStep
                    
                    val ctxText = "Current: $currStep | Next: ${nextStep ?: "None"}"
                    hud.updateHud(
                        stepHeader = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Look for '$currStep'",
                        contextText = ctxText,
                        subTag = "dynamic-fast-forward"
                    )
                    triggerSearchPipeline(currStep, activePackage, activeScreenName)
                    return@postToMain
                }
            }

            if (isLauncherEvent && !currStepIsHome) {
                val targetName = targetPackage.replaceFirstChar { it.uppercase() }
                hud.updateHud(
                    stepHeader = "ON HOME",
                    actionInstruction = "Open $targetName",
                    contextText = "Expected: $currStep",
                    subTag = "on-home-diverged"
                )
                return@postToMain
            } else if (!currStepIsHome && !inTargetApp && !isLauncherEvent) {
                val targetName = targetPackage.replaceFirstChar { it.uppercase() }
                val actualAppName = getAppLabel(activePackage) ?: activeScreenName
                hud.updateHud(
                    stepHeader = "WRONG APP",
                    actionInstruction = "Open $targetName or return to Home",
                    contextText = "Current: $actualAppName | Expected: $targetName",
                    subTag = "pkg-mismatch"
                )
                return@postToMain
            }

            val screenNorm = activeScreenName.trim().lowercase()
            val currNorm = currStep.trim().lowercase()
            val nextNorm = nextStep?.trim()?.lowercase()

            if (isLauncherEvent && currStepIsHome) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            val currStepIsAppRoot = appRootFuzzyMatch(currNorm, targetPackage)
            if (currStepIsAppRoot && inTargetApp) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            if (fuzzyMatch(screenNorm, currNorm)) {
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            if (nextNorm != null && fuzzyMatch(screenNorm, nextNorm)) {
                prevStep = currStep
                currentIndex++
                currStep = pathSteps[currentIndex]
                nextStep = pathSteps.getOrNull(currentIndex + 1)
                lastCorrectStep = currStep
                advanceStep(currentIndex)
                return@postToMain
            }

            if (inTargetApp) {
                // We are in the correct app! Let's search for the current step (or next step) on this screen.
                triggerSearchPipeline(currStep, activePackage, activeScreenName)
                return@postToMain
            }

            val lcs = lastCorrectStep
            if (lcs != null) {
                hud.updateHud(
                    stepHeader = "OFF TRACK",
                    actionInstruction = "Press Back to return to '$lcs'",
                    contextText = "Current: $activeScreenName | Expected: $currStep",
                    subTag = "off-track"
                )
            } else {
                val targetName = targetPackage.replaceFirstChar { it.uppercase() }
                hud.updateHud(
                    stepHeader = "OFF TRACK",
                    actionInstruction = "Open $targetName",
                    contextText = "Current: $activeScreenName | Expected: $currStep",
                    subTag = "off-track-no-anchor"
                )
            }
        }
    }

    private fun advanceStep(confirmedIndex: Int) {
        val stepNum = confirmedIndex + 1

        if (nextStep == null) {
            Log.i(TAG, "COMPLETE - step $stepNum/'$currStep' was the last step")
            hud.showVerificationPrompt(
                onYes = {
                    hud.resetFocusability()
                    handleSuccess()
                },
                onNo = {
                    hud.resetFocusability()
                    triggerHealer(true)
                }
            )
            return
        }

        prevStep = currStep
        currentIndex++
        currStep = pathSteps[currentIndex]
        nextStep = pathSteps.getOrNull(currentIndex + 1)

        val ctxText = "Current: $currStep | Next: ${nextStep ?: "None"}"
        hud.updateHud(
            stepHeader = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
            actionInstruction = "Look for '$currStep'",
            contextText = ctxText,
            subTag = "on-track"
        )
        
        triggerSearchPipeline(currStep, lastKnownActivePackage, activeScreenName)
    }

    private fun triggerSearchPipeline(targetStep: String, activePkg: String, activeScreen: String) {
        if (!::elementFinder.isInitialized) return
        
        stateMachineScope.launch(Dispatchers.IO) {
            delay(400) 
            if (!isNavigating) return@launch
            
            // Search for current step
            val result = elementFinder.findElement(targetStep, activePkg, activeScreen, prevStep ?: "")
            currentTargetBounds = result.bounds
            
            if (result.found) {
                postToMain {
                    if (!isNavigating) return@postToMain
                    hud.updateHud(
                        stepHeader = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Tap '$targetStep'",
                        contextText = "Current: $activeScreenName | Next: ${nextStep ?: "None"}",
                        subTag = "target-found"
                    )
                }
                return@launch
            }
            
            // If current step is missing, check if we accidentally advanced to the NEXT step already
            val nStep = nextStep
            if (nStep != null) {
                val nextResult = elementFinder.findElement(nStep, activePkg, activeScreen, targetStep)
                if (nextResult.found) {
                    Log.i(TAG, "Current step '$targetStep' missing but next step '$nStep' found! Advancing.")
                    postToMain {
                        if (!isNavigating) return@postToMain
                        lastCorrectStep = targetStep
                        advanceStep(currentIndex)
                    }
                    return@launch
                }
            }
            
            postToMain {
                if (!isNavigating) return@postToMain
                
                val hasScrollable = elementFinder.hasScrollableContainer()
                if (hasScrollable) {
                    Log.i(TAG, "Target missing, scrollable container found. Prompting user to scroll.")
                    hud.updateHud(
                        stepHeader = "STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Scroll to find '$targetStep'",
                        contextText = "Current: $activeScreenName | Next: ${nextStep ?: "None"}",
                        subTag = "scroll-directive"
                    )
                } else {
                    Log.i(TAG, "Target missing, dead end. Stage 4 Groq Healing needed.")
                    hud.updateHud(
                        stepHeader = "AI HEALING",
                        actionInstruction = "Finding alternate path…",
                        contextText = "Current: $targetStep | Next: ${nextStep ?: "None"}",
                        subTag = "healing"
                    )
                    triggerHealer(false)
                }
            }
        }
    }
    
    private fun triggerHealer(blacklistedFailedSequence: Boolean) {
        stateMachineScope.launch(Dispatchers.IO) {
            val cleanPageStr = try { File(context.filesDir, "clean_page.json").readText() } catch (e: Exception) { "{}" }
            
            val failedSeq = if (blacklistedFailedSequence) pathSteps else pathSteps.take(currentIndex + 1)
            
            val newSubPath = GroqHealer.requestHealing(
                context = context,
                targetPackage = targetPackage,
                initialIntent = initialTask,
                failedSequence = failedSeq,
                cleanPageJsonStr = cleanPageStr
            )
            
            postToMain {
                if (!isNavigating || newSubPath == null || newSubPath.isEmpty()) {
                    hud.updateHud(
                        stepHeader = "HEALING FAILED",
                        actionInstruction = "No alternate path found.",
                        subTag = "healing-failed"
                    )
                    return@postToMain
                }
                
                if (newSubPath.size == 1 && newSubPath[0].uppercase() == "BACK") {
                    if (currentIndex > 0) currentIndex--
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    hud.updateHud(
                        stepHeader = "HEALED",
                        actionInstruction = "Press Back",
                        contextText = "Going back to $currStep"
                    )
                } else {
                    val stepsBefore = pathSteps.take(currentIndex)
                    val stepsAfter = pathSteps.drop(currentIndex + 1) // drops the failed step
                    pathSteps = (stepsBefore + newSubPath + stepsAfter).toMutableList()
                    currStep = pathSteps[currentIndex]
                    nextStep = pathSteps.getOrNull(currentIndex + 1)
                    
                    hud.updateHud(
                        stepHeader = "HEALED - STEP ${currentIndex + 1} OF ${pathSteps.size}",
                        actionInstruction = "Tap '$currStep'",
                        contextText = "Updated route"
                    )
                }
            }
        }
    }
    
    private fun handleSuccess() {
        stateMachineScope.launch(Dispatchers.IO) {
            val pathStr = pathSteps.joinToString(" -> ")
            Log.d(TAG, "Saving path to Cloud: $pathStr")
            
            Log.i("[NetworkClient]", "Uploading successful path to Firestore: $pathStr")
            CloudPathDatabase.addEntry(targetPackage, initialTask, pathStr)
            
            postToMain {
                hud.updateHud(
                    stepHeader = "SUCCESS",
                    actionInstruction = "Path saved successfully!",
                    subTag = "saved"
                )
                isNavigating = false
                mainHandler.postDelayed({ hud.hide() }, 3000L)
            }
        }
    }

    fun isTargetApp(activePackage: String, targetApp: String, currentExpectedStep: String?): Boolean {
        val pkg = activePackage.lowercase()
        val target = targetApp.lowercase()
        if (pkg.contains(target)) return true
        
        // Also check if the active package contains the expected step (e.g. step is "Clock" and pkg is "com.android.deskclock")
        if (currentExpectedStep != null && currentExpectedStep.isNotBlank()) {
            val stepLower = currentExpectedStep.lowercase()
            if (pkg.contains(stepLower)) return true
            if (stepLower == "clock" && pkg.contains("deskclock")) return true
        }
        
        // Generalised check using PackageManager label
        try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(activePackage, 0)
            val label = pm.getApplicationLabel(info).toString().lowercase()
            if (label.contains(target) || target.contains(label)) return true
            if (currentExpectedStep != null && currentExpectedStep.isNotBlank()) {
                val stepLower = currentExpectedStep.lowercase()
                if (label.contains(stepLower) || stepLower.contains(label)) return true
            }
        } catch (e: Exception) {
            // Ignore
        }
        
        return false
    }

    private fun getAppLabel(packageName: String): String? {
        if (!::context.isInitialized) return null
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            null
        }
    }

    private fun appRootFuzzyMatch(stepNorm: String, targetPkg: String): Boolean {
        val pkgSimple = targetPkg.substringAfterLast('.')
        return stepNorm == targetPkg ||
               stepNorm == pkgSimple ||
               targetPkg.contains(stepNorm, ignoreCase = true) ||
               pkgSimple.contains(stepNorm, ignoreCase = true) ||
               stepNorm.contains(pkgSimple, ignoreCase = true)
    }

    private fun fuzzyMatch(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        if (actual.contains(expected) || expected.contains(actual)) return true
        if (expected.length < 20) {
            val words = expected.split(" ", "_", "-").filter { it.length > 2 }
            if (words.isNotEmpty() && words.all { actual.contains(it) }) return true
        }
        return false
    }

    fun getDefaultLauncherPackage(): String? {
        if (!::context.isInitialized) return null
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            info?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
    }

    private fun postToMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post(block)
    }
}
