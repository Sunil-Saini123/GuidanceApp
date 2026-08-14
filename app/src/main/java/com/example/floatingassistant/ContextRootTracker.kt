package com.example.floatingassistant

import android.util.Log

/**
 * ContextRootTracker — Phase 4
 *
 * Tracks the navigation stack per app package so we know which "root screen"
 * the user is currently on.
 *
 * Rules:
 *  - TYPE_WINDOW_STATE_CHANGED with a NEW className → forward navigation → push
 *  - TYPE_WINDOW_STATE_CHANGED with an EXISTING className → back navigation → pop to it
 *  - Package change → previous context is abandoned (user left the app)
 *
 * Root name is derived from the Activity/Fragment class name:
 *   "com.android.settings.bluetooth.BluetoothSettings" → "BluetoothSettings"
 *   "com.android.settings.Settings"                   → "Settings"
 */
class ContextRootTracker {

    companion object {
        private const val TAG = "ContextRoot"
    }

    /** Navigation result returned to the caller on every navigate() call. */
    data class NavResult(
        val rootName: String,   // Clean root name (simple class name)
        val isBack: Boolean,    // True if this was detected as back navigation
        val isNew: Boolean      // True if this root has never been seen in this session
    )

    // packageName → ordered stack of root names (first = deepest history, last = current)
    private val navStacks = HashMap<String, ArrayDeque<String>>()

    // Track all root names ever seen per package (for isNew detection)
    private val everSeen = HashMap<String, MutableSet<String>>()

    /**
     * Called on every TYPE_WINDOW_STATE_CHANGED event.
     *
     * @param packageName  Package of the current window.
     * @param className    Activity or Fragment class name from the event (may be empty).
     * @return [NavResult] with the resolved root name and navigation direction.
     */
    fun navigate(packageName: String, className: String): NavResult {
        val rootName = extractRootName(className).ifEmpty { packageName.substringAfterLast('.') }
        val stack    = navStacks.getOrPut(packageName) { ArrayDeque() }
        val seen     = everSeen.getOrPut(packageName)  { mutableSetOf() }

        val isNew    = rootName !in seen
        seen += rootName

        // Check if this root already exists in the stack → back navigation
        val existingIdx = stack.indexOfLast { it == rootName }
        val isBack = existingIdx >= 0

        if (isBack) {
            // Pop everything above the existing entry (user went back)
            while (stack.size > existingIdx + 1) stack.removeLast()
            Log.d(TAG, "Back nav  [$packageName] → $rootName  (stack depth ${stack.size})")
        } else {
            stack.addLast(rootName)
            Log.d(TAG, "Forward nav [$packageName] → $rootName  (stack depth ${stack.size})")
        }

        return NavResult(rootName = rootName, isBack = isBack, isNew = isNew)
    }

    /** Returns the current root for a package, or null if no navigation recorded yet. */
    fun currentRoot(packageName: String): String? = navStacks[packageName]?.lastOrNull()

    /** Fully resets a package's state (e.g. app was force-closed). */
    fun reset(packageName: String) {
        navStacks.remove(packageName)
        everSeen.remove(packageName)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Extracts the simple class name from a fully-qualified Android class name.
     * Strips known boilerplate suffixes for readability.
     *
     * "com.android.settings.bluetooth.BluetoothSettings" → "Bluetooth"
     * "com.android.settings.Settings"                   → "Settings"
     * "com.whatsapp.Main2Activity"                      → "Main2"
     */
    private fun extractRootName(className: String): String {
        val simple = className.substringAfterLast('.')
        return simple
            .removeSuffix("Activity")
            .removeSuffix("Fragment")
            .removeSuffix("Settings")
            .removeSuffix("Screen")
            .ifEmpty { simple }
    }
}
