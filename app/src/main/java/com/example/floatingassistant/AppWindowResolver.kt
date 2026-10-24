package com.example.floatingassistant

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

object AppWindowResolver {

    /**
     * [PERMANENT FIX] Do not remove this!
     * Finds the foreground application window root node, skipping system UI and overlays.
     * In multi-window or split-screen environments, prioritizes active or focused application windows.
     * Full-screen application activities (including MainActivity, Settings, WhatsApp) have TYPE_APPLICATION.
     * Floating overlays (bubble, HUD, dialogs) have TYPE_APPLICATION_OVERLAY / TYPE_ACCESSIBILITY_OVERLAY.
     * Only excludes [excludePackage] from fallback non-application windows, never from true TYPE_APPLICATION activities.
     */
    fun getUnderlyingAppRoot(service: AccessibilityService, excludePackage: String? = null): AccessibilityNodeInfo? {
        val windows = try { service.windows } catch (e: Exception) { null }

        if (!windows.isNullOrEmpty()) {
            // Priority 1: Focused or Active application window (handles split-screen, PiP, multi-window)
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION && (window.isFocused || window.isActive)) {
                    val root = window.root
                    val pkg = root?.packageName?.toString()
                    if (pkg != null && !pkg.startsWith("com.android.systemui")) {
                        return root
                    }
                    root?.recycle()
                }
            }

            // Priority 2: Any visible application window not belonging to system UI
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_APPLICATION) {
                    val root = window.root
                    val pkg = root?.packageName?.toString()
                    if (pkg != null && !pkg.startsWith("com.android.systemui")) {
                        return root
                    }
                    root?.recycle()
                }
            }

            // Priority 3: Fallback to any non-system window (excluding overlay package if specified)
            for (window in windows) {
                val root = window.root
                val pkg = root?.packageName?.toString()
                if (pkg != null && (excludePackage == null || pkg != excludePackage) && !pkg.startsWith("com.android.systemui")) {
                    return root
                }
                root?.recycle()
            }
        }

        // Final fallback: rootInActiveWindow, but guard against system UI and overlay windows
        val fallbackRoot = service.rootInActiveWindow ?: return null
        val fallbackPkg = fallbackRoot.packageName?.toString()
        if (fallbackPkg == null || (excludePackage != null && fallbackPkg == excludePackage) || fallbackPkg.startsWith("com.android.systemui")) {
            fallbackRoot.recycle()
            return null
        }
        return fallbackRoot
    }
}
