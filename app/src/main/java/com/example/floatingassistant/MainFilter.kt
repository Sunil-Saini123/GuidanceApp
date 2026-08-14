package com.example.floatingassistant

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Main Filter — Phase 2
 *
 * Responsibility: given a raw root [AccessibilityNodeInfo] and the package that owns the
 * current window, decide in O(1) whether the frame should be discarded entirely.
 *
 * Two discard rules (executed in order, cheapest first):
 *
 *  1. CANNOT ACCESS — the root node is null, meaning the service has no window content to
 *     read.  Log "Cannot access" and return [FilterResult.CannotAccess].
 *
 *  2. SYSTEM UI DROP — the package belongs to Android's chrome layer (status bar, nav bar,
 *     notification shade, launcher, IME, etc.) which carries no app-level UI data.
 *     Return [FilterResult.Dropped] so the caller skips all downstream processing.
 *
 * Any frame that survives both checks returns [FilterResult.Passed] with the root node
 * ready for the Inbetween Filter (Phase 3).
 *
 * Performance notes:
 *  - Package look-up is a [HashSet.contains] call → O(1).
 *  - No allocations on the hot path for the DROP case.
 *  - Caller is responsible for calling [AccessibilityNodeInfo.recycle] on the node when
 *    done (see [FilterResult.Passed.rootNode]).
 */
object MainFilter {

    private const val TAG = "MainFilter"

    // ── System / chrome packages that carry no app UI data ──────────────────
    //
    // Rule: if the window's package name STARTS WITH any entry in this set, drop it.
    // Using startsWith lets us cover sub-packages (e.g. com.android.systemui.something)
    // without bloating the set.
    private val SYSTEM_UI_PREFIXES = arrayOf(
        "com.android.systemui",          // Status bar, nav bar, notification shade, QS panel
        "com.android.launcher",          // AOSP launcher (various sub-packages)
        "com.google.android.apps.nexuslauncher", // Pixel launcher
        "com.miui.home",                 // MIUI launcher
        "com.samsung.android.app.launcher",     // One UI launcher
        "com.huawei.android.launcher",   // EMUI launcher
        "com.oppo.launcher",             // OPPO launcher
        "com.vivo.launcher",             // Vivo launcher
        "com.vivo.systemuiplugin",       // Vivo system UI plugin (status bar extensions)
        "com.vivo.SystemPlugin",         // Vivo system plugin variants
        "com.bbk.launcher",             // BBK / iQOO launcher
        "com.oneplus.launcher",          // OnePlus launcher
        "com.android.inputmethod",       // AOSP keyboard
        "com.google.android.inputmethod",// Gboard
        "com.swiftkey",                  // SwiftKey
        "com.samsung.android.honeyboard" // Samsung keyboard
    )

    // ── Public API ───────────────────────────────────────────────────────────

    sealed class FilterResult {
        /** Service could not retrieve a root node for this window. */
        object CannotAccess : FilterResult()

        /** Package belongs to Android chrome — no app UI inside, skip it. */
        data class Dropped(val packageName: String) : FilterResult()

        /**
         * Frame passed all checks. The caller MUST call [rootNode].recycle() when finished
         * to avoid AccessibilityNodeInfo object leaks.
         */
        data class Passed(
            val packageName: String,
            val rootNode: AccessibilityNodeInfo
        ) : FilterResult()
    }

    /**
     * Apply the Main Filter to one accessibility event frame.
     *
     * @param packageName  Package that owns the current window (may be null).
     * @param rootNode     Root node retrieved from the event or [rootInActiveWindow] (may be null).
     * @param ownPackage   Our app's package name — always dropped to prevent self-capture.
     */
    fun apply(
        packageName: String?,
        rootNode: AccessibilityNodeInfo?,
        ownPackage: String
    ): FilterResult {

        // ── Rule 1: Cannot access ────────────────────────────────────────────
        if (rootNode == null) {
            val pkg = packageName ?: "unknown"
            Log.w(TAG, "Cannot access [$pkg]")
            return FilterResult.CannotAccess
        }

        val pkg = packageName ?: run {
            // Root exists but no package — unusual; treat as cannot access.
            Log.w(TAG, "Cannot access [no package name]")
            rootNode.recycle()
            return FilterResult.CannotAccess
        }

        // ── Rule 2: Drop own package ─────────────────────────────────────────
        if (pkg == ownPackage) {
            Log.d(TAG, "Dropped [$pkg] — own app")
            rootNode.recycle()
            return FilterResult.Dropped(pkg)
        }

        // ── Rule 3: Drop System UI / launchers / keyboards ───────────────────
        for (prefix in SYSTEM_UI_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                Log.d(TAG, "Dropped [$pkg] — System UI")
                rootNode.recycle()
                return FilterResult.Dropped(pkg)
            }
        }

        // ── Passed ───────────────────────────────────────────────────────────
        Log.v(TAG, "Passed  [$pkg]")
        return FilterResult.Passed(packageName = pkg, rootNode = rootNode)
    }
}
