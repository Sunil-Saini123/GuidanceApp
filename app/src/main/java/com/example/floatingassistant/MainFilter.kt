package com.example.floatingassistant

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Main Filter — Phase 2 & Stage 3.5 Semantic Root Naming
 *
 * Determines if an accessibility event should be processed, and generates a
 * clean, human-readable semantic root name for the current screen.
 *
 * The base app name is now resolved via PackageManager so that:
 *   com.android.deskclock  -> "Clock"
 *   com.google.android.gm -> "Gmail"
 *   com.whatsapp           -> "WhatsApp"
 */
object MainFilter {

    private const val TAG = "MainFilter"

    private val SYSTEM_UI_PREFIXES = arrayOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.miui.home",
        "com.samsung.android.app.launcher",
        "com.huawei.android.launcher",
        "com.oppo.launcher",
        "com.vivo.launcher",
        "com.vivo.systemuiplugin",
        "com.vivo.SystemPlugin",
        "com.bbk.launcher",
        "com.oneplus.launcher",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.swiftkey",
        "com.samsung.android.honeyboard"
    )

    sealed class FilterResult {
        object CannotAccess : FilterResult()
        data class Dropped(val packageName: String) : FilterResult()
        data class Passed(
            val packageName: String,
            val rootNode: AccessibilityNodeInfo,
            val semanticRootName: String,
            /** Human-readable display name of the app, e.g. "Clock", "Gmail" */
            val appLabel: String
        ) : FilterResult()
    }

    fun apply(
        context: Context,
        packageName: String?,
        rootNode: AccessibilityNodeInfo?,
        ownPackage: String,
        launcherPackage: String?,
        eventClassName: String? = null
    ): FilterResult {
        if (rootNode == null) {
            val pkg = packageName ?: "unknown"
            Log.w(TAG, "Cannot access [$pkg]")
            return FilterResult.CannotAccess
        }

        val pkg = packageName ?: run {
            Log.w(TAG, "Cannot access [no package name]")
            rootNode.recycle()
            return FilterResult.CannotAccess
        }

        if (pkg == ownPackage) {
            Log.d(TAG, "Dropped [$pkg] — own app")
            rootNode.recycle()
            return FilterResult.Dropped(pkg)
        }

        for (prefix in SYSTEM_UI_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                Log.d(TAG, "Dropped [$pkg] — System UI")
                rootNode.recycle()
                return FilterResult.Dropped(pkg)
            }
        }

        // Resolve the human-readable app label from PackageManager
        val appLabel = resolveAppLabel(context, pkg)

        // If this is the launcher/home screen, always name it "Home"
        val semanticName = if (launcherPackage != null && pkg.startsWith(launcherPackage)) {
            "Home"
        } else {
            generateSemanticRootName(appLabel, rootNode, eventClassName)
        }

        Log.v(TAG, "Passed [$pkg] -> appLabel='$appLabel' semanticRoot='$semanticName'")

        return FilterResult.Passed(
            packageName = pkg,
            rootNode = rootNode,
            semanticRootName = semanticName,
            appLabel = appLabel
        )
    }

    /**
     * Resolves a human-readable app name from PackageManager.
     * Falls back to the last segment of the package name if PM lookup fails.
     */
    fun resolveAppLabel(context: Context, packageName: String): String {
        return try {
            val pm = context.packageManager
            val info = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(info).toString()
        } catch (e: Exception) {
            // Fallback: last segment of package name, capitalised
            packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    private fun generateSemanticRootName(
        appLabel: String,
        rootNode: AccessibilityNodeInfo,
        eventClassName: String?
    ): String {
        // Base is the real app label ("Clock", "WhatsApp", "Gmail")
        val baseAppName = appLabel.trim()

        // Contextual Identifier — in priority order:
        var contextId = findSelectedTab(rootNode)

        if (contextId == null) {
            contextId = findTopTitle(rootNode)
        }

        if (contextId == null) {
            val cls = eventClassName?.substringAfterLast('.')
            if (cls != null &&
                !cls.contains("Layout", ignoreCase = true) &&
                !cls.contains("View", ignoreCase = true) &&
                !cls.contains("Activity", ignoreCase = true)) {
                contextId = cls
            }
        }

        // If context matches the base name (e.g. tab says "Clock"), skip it to avoid "Clock-clock"
        if (contextId != null && contextId.equals(baseAppName, ignoreCase = true)) {
            contextId = null
        }

        return if (contextId.isNullOrBlank()) {
            baseAppName
        } else {
            val cleanContext = contextId.trim()
                .replace(Regex("[^a-zA-Z0-9]+"), "-")
                .take(25)
                .removeSuffix("-")
            "$baseAppName-$cleanContext"
        }
    }

    private fun findSelectedTab(node: AccessibilityNodeInfo): String? {
        if (node.isSelected) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length < 30) return text
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val res = findSelectedTab(child)
                if (res != null) return res
            } finally {
                child.recycle()
            }
        }
        return null
    }

    private fun findTopTitle(node: AccessibilityNodeInfo): String? {
        val rootBounds = Rect()
        node.getBoundsInScreen(rootBounds)
        val screenHeight = rootBounds.height()
        if (screenHeight <= 0) return null

        val top15 = screenHeight * 0.15f
        return searchTopTitle(node, top15)
    }

    private fun searchTopTitle(node: AccessibilityNodeInfo, topThreshold: Float): String? {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        // Skip nodes entirely below the top 15%
        if (bounds.top > topThreshold) return null

        val resId = node.viewIdResourceName?.lowercase() ?: ""
        if (resId.contains("title") || resId.contains("header") || resId.contains("action_bar") || resId.contains("toolbar")) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length < 40) return text
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                val res = searchTopTitle(child, topThreshold)
                if (res != null) return res
            } finally {
                child.recycle()
            }
        }
        return null
    }
}
