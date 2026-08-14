package com.example.floatingassistant

import android.util.Log

/**
 * OemRomDetector — Phase 8
 *
 * Detects whether the device is running a common OEM custom Android ROM by
 * using reflection to invoke the hidden [android.os.SystemProperties.get]
 * method and probing well-known vendor property keys.
 *
 * Supported ROMs:
 *  - Xiaomi MIUI / HyperOS  → ro.miui.ui.version.name
 *  - vivo OriginOS / Funtouch → ro.vivo.os.name + ro.vivo.os.version
 *  - OPPO / Realme ColorOS  → ro.build.version.coloros
 *  - Samsung One UI          → ro.build.version.oneui
 *
 * All reflection calls are wrapped in try/catch. If probing fails for any
 * reason (SecurityManager, class not found, etc.) the module silently returns
 * [OemResult.Stock] and logs a Debug-level warning. It will never throw or
 * crash the caller.
 *
 * Thread-safety: stateless — safe to call from any thread.
 */
object OemRomDetector {

    private const val TAG = "OemRomDetector"

    // ── Public result type ─────────────────────────────────────────────────────

    sealed class OemResult {
        /** Device is running stock AOSP / unrecognised ROM. */
        object Stock : OemResult()

        /**
         * Device is running a known custom OEM ROM.
         * @param name    Human-readable OS name, e.g. "MIUI", "HyperOS", "ColorOS".
         * @param version Version string as reported by the system property, e.g. "V14".
         */
        data class Custom(val name: String, val version: String) : OemResult()
    }

    // ── Main detection entry point ─────────────────────────────────────────────

    /**
     * Run all OEM property probes and return the first match, or [OemResult.Stock].
     * Call order determines priority: MIUI → vivo → ColorOS → Samsung.
     */
    fun detect(): OemResult {
        return detectMiui()
            ?: detectVivo()
            ?: detectColorOs()
            ?: detectOneUi()
            ?: OemResult.Stock
    }

    // ── Per-OEM detectors ──────────────────────────────────────────────────────

    /**
     * Xiaomi MIUI / HyperOS
     * Key: ro.miui.ui.version.name  (e.g. "V14", "V816.0.6.0" for HyperOS)
     * HyperOS reports its marketing name via ro.mi.os.version.name on newer builds;
     * we check the MIUI property first since it is present on both.
     */
    private fun detectMiui(): OemResult.Custom? {
        val value = readProperty("ro.miui.ui.version.name")
        if (!value.isNullOrBlank()) {
            // HyperOS build strings start with "V8xx", MIUI ones start with "V1x"
            val osName = if (value.length >= 3 && value[1] == '8') "HyperOS" else "MIUI"
            Log.d(TAG, "Detected $osName — ro.miui.ui.version.name=$value")
            return OemResult.Custom(name = osName, version = value)
        }
        return null
    }

    /**
     * vivo OriginOS / Funtouch OS
     * Keys: ro.vivo.os.name (e.g. "OriginOS", "Funtouch OS")
     *        ro.vivo.os.version (e.g. "4.0")
     */
    private fun detectVivo(): OemResult.Custom? {
        val name    = readProperty("ro.vivo.os.name")
        val version = readProperty("ro.vivo.os.version")
        if (!name.isNullOrBlank()) {
            val resolvedVersion = version.orEmpty().ifBlank { "unknown" }
            Log.d(TAG, "Detected vivo ROM: $name $resolvedVersion")
            return OemResult.Custom(name = name, version = resolvedVersion)
        }
        return null
    }

    /**
     * OPPO / Realme ColorOS
     * Key: ro.build.version.coloros (e.g. "V13.1")
     */
    private fun detectColorOs(): OemResult.Custom? {
        val value = readProperty("ro.build.version.coloros")
        if (!value.isNullOrBlank()) {
            Log.d(TAG, "Detected ColorOS — version=$value")
            return OemResult.Custom(name = "ColorOS", version = value)
        }
        return null
    }

    /**
     * Samsung One UI
     * Key: ro.build.version.oneui (integer, e.g. 50100 = One UI 5.1)
     * We convert the raw integer to a human-readable major.minor string.
     */
    private fun detectOneUi(): OemResult.Custom? {
        val value = readProperty("ro.build.version.oneui")
        if (!value.isNullOrBlank()) {
            val version = formatOneUiVersion(value)
            Log.d(TAG, "Detected One UI — raw=$value formatted=$version")
            return OemResult.Custom(name = "One UI", version = version)
        }
        return null
    }

    // ── Reflection helper ──────────────────────────────────────────────────────

    /**
     * Invoke [android.os.SystemProperties.get(String)] via reflection.
     * Returns the trimmed, non-empty property value, or null if the property
     * does not exist, is blank, or if reflection fails for any reason.
     */
    private fun readProperty(key: String): String? {
        return try {
            val clazz  = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val result = method.invoke(null, key) as? String
            result?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.d(TAG, "readProperty($key) failed — ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    // ── Formatting helpers ─────────────────────────────────────────────────────

    /**
     * Samsung encodes the One UI version as a 5-digit integer:
     *   50100 → "5.1"
     *   60000 → "6.0"
     * Format: MMMMN (M = major×10000, N = minor×100 in the last two digits)
     * We try to parse it; if the raw string isn't a number we return it as-is.
     */
    private fun formatOneUiVersion(raw: String): String {
        return try {
            val intValue = raw.trim().toInt()
            val major = intValue / 10000
            val minor = (intValue % 10000) / 100
            "$major.$minor"
        } catch (_: NumberFormatException) {
            raw.trim()
        }
    }
}
