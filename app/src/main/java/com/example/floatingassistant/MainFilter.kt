package com.example.floatingassistant

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Main Filter — Phase 2 & Stage 3.5 Semantic Root Naming
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
            val semanticRootName: String
        ) : FilterResult()
    }

    fun apply(
        packageName: String?,
        rootNode: AccessibilityNodeInfo?,
        ownPackage: String,
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

        val semanticName = generateSemanticRootName(pkg, rootNode, eventClassName)
        Log.v(TAG, "Passed [$pkg] -> Semantic Root: $semanticName")
        
        return FilterResult.Passed(packageName = pkg, rootNode = rootNode, semanticRootName = semanticName)
    }

    private fun generateSemanticRootName(
        packageName: String,
        rootNode: AccessibilityNodeInfo,
        eventClassName: String?
    ): String {
        // 1. Base App Name
        val baseAppName = packageName.substringAfterLast('.').lowercase()

        // 2. Contextual Identifier
        var contextId = findSelectedTab(rootNode)
        
        if (contextId == null) {
            contextId = findTopTitle(rootNode)
        }
        
        if (contextId == null) {
            val cls = eventClassName?.substringAfterLast('.')
            if (cls != null && !cls.contains("Layout", ignoreCase = true) && !cls.contains("View", ignoreCase = true)) {
                contextId = cls
            }
        }
        
        if (contextId == null) {
            contextId = "main"
        }

        // Clean up the contextId
        val cleanContext = contextId.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .take(20)
            .removeSuffix("-")
            
        return "$baseAppName-$cleanContext"
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
        if (bounds.bottom > topThreshold && bounds.top > topThreshold) {
            // Node is entirely below the top 15%, since we are doing DFS, its children are also below.
            // Wait, a container might span the whole screen. We should check if the node itself is entirely below.
            if (bounds.top > topThreshold) return null
        }

        val resId = node.viewIdResourceName?.lowercase() ?: ""
        if (resId.contains("title") || resId.contains("header") || resId.contains("action_bar")) {
            val text = node.text?.toString() ?: node.contentDescription?.toString()
            if (!text.isNullOrBlank() && text.length < 30) return text
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
