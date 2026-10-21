package com.example.floatingassistant

import android.content.Context
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

        // [PERMANENT FIX] Do not remove this! The caller (UiTreeAccessibilityService) owns rootNode lifecycle
        // and recycles it upon non-Passed filter results. Removing premature rootNode.recycle() here prevents
        // native Binder double-recycle pool corruption.
        val pkg = packageName ?: run {
            Log.w(TAG, "Cannot access [no package name]")
            return FilterResult.CannotAccess
        }

        if (pkg == ownPackage) {
            Log.d(TAG, "Dropped [$pkg] — own app")
            return FilterResult.Dropped(pkg)
        }

        for (prefix in SYSTEM_UI_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                Log.d(TAG, "Dropped [$pkg] — System UI")
                return FilterResult.Dropped(pkg)
            }
        }

        // Resolve the human-readable app label from PackageManager
        val appLabel = resolveAppLabel(context, pkg)

        // If this is the launcher/home screen, always name it "Home"
        val semanticName = if (launcherPackage != null && pkg.startsWith(launcherPackage)) {
            "Home"
        } else {
            generateSemanticRootName(appLabel, rootNode, eventClassName, pkg)
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

    // [PERMANENT FIX] Do not remove this! Universal ScreenTitleResolver prevents SIM card hijacking and naive DFS misnaming
    private fun generateSemanticRootName(
        appLabel: String,
        rootNode: AccessibilityNodeInfo,
        eventClassName: String?,
        packageName: String
    ): String {
        return ScreenTitleResolver.resolveTitle(
            rootNode = rootNode,
            appLabel = appLabel,
            packageName = packageName,
            eventClassName = eventClassName
        )
    }
}
