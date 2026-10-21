package com.example.floatingassistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

object AppWindowResolver {

    /**
     * Finds the underlying foreground application window root node, skipping our own overlay
     * and system UI. In multi-window or split-screen environments, prioritizes active or focused
     * application windows.
     */
    fun getUnderlyingAppRoot(service: AccessibilityService, excludePackage: String): AccessibilityNodeInfo? {
        val windows = try { service.windows } catch (e: Exception) { null }

        if (!windows.isNullOrEmpty()) {
            // [PERMANENT FIX] Do not remove this! Priority 1: Focused or Active application window (handles split-screen, PiP, multi-window)
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                    val root = window.root
                    val pkg = root?.packageName?.toString()
                    if (pkg != null && pkg != excludePackage && !pkg.startsWith("com.android.systemui")) {
                        return root
                    }
                    root?.recycle()
                }
            }

            // Priority 2: Any visible application window not belonging to our overlay or system UI
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    val pkg = root?.packageName?.toString()
                    if (pkg != null && pkg != excludePackage && !pkg.startsWith("com.android.systemui")) {
                        return root
                    }
                    root?.recycle()
                }
            }

            // Priority 3: Fallback to any non-system, non-overlay window
            for (window in windows) {
                val root = window.root
                val pkg = root?.packageName?.toString()
                if (pkg != null && pkg != excludePackage && !pkg.startsWith("com.android.systemui")) {
                    return root
                }
                root?.recycle()
            }
        }

        // Final fallback: rootInActiveWindow, but strictly guard against returning our own overlay or system UI
        val fallbackRoot = service.rootInActiveWindow ?: return null
        val fallbackPkg = fallbackRoot.packageName?.toString()
        if (fallbackPkg == null || fallbackPkg == excludePackage || fallbackPkg.startsWith("com.android.systemui")) {
            fallbackRoot.recycle()
            return null
        }
        return fallbackRoot
    }
}
