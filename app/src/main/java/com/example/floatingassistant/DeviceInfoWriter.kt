package com.example.floatingassistant

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * DeviceInfoWriter
 *
 * Responsibility: capture the device's Android version, OS build details,
 * manufacturer/model info, and (Phase 8) custom OEM ROM metadata, then
 * persist it as a JSON file. This is collected at the permission step
 * (i.e. when the user reaches / interacts with the Accessibility permission
 * screen in [MainActivity]) so we have a snapshot of what device + OS
 * granted the permission.
 *
 * Output format:
 * ```json
 * {
 *   "manufacturer": "Samsung",
 *   "brand": "samsung",
 *   "model": "SM-S911B",
 *   "device": "dm3q",
 *   "product": "dm3qxeea",
 *   "android_version": "14",
 *   "sdk_int": 34,
 *   "release_codename": "UpsideDownCake",
 *   "build_id": "UP1A.231005.007",
 *   "fingerprint": "samsung/dm3qxeea/dm3q:14/...",
 *   "hardware": "qcom",
 *   "board": "kalama",
 *   "supported_abis": ["arm64-v8a", "armeabi-v7a", "armeabi"],
 *   "is_emulator": false,
 *   "custom_os": "One UI",
 *   "custom_os_version": "6.0",
 *   "captured_at": "2026-08-12T10:15:30Z",
 *   "captured_at_step": "permission_request"
 * }
 * ```
 * On stock/unrecognised ROMs `custom_os` and `custom_os_version` are both "stock".
 *
 * Performance:
 *  - Serialization runs on [Dispatchers.IO] — never blocks the main thread.
 *  - Uses the caller's [CoroutineScope]; fire-and-forget.
 *  - File is placed in [Context.filesDir] by default so it persists for the
 *    lifetime of the app install (unlike cacheDir, which the OS may clear).
 */
object DeviceInfoWriter {

    private const val TAG = "DeviceInfoWriter"
    const val FILE_NAME = "device_info.json"

    /**
     * Build the device info JSON synchronously and return it as a [JSONObject].
     * Cheap (no I/O) — safe to call from the main thread.
     */
    fun collect(step: String = "permission_request"): JSONObject {
        val utcFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        return JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER ?: "unknown")
            put("brand", Build.BRAND ?: "unknown")
            put("model", Build.MODEL ?: "unknown")
            put("device", Build.DEVICE ?: "unknown")
            put("product", Build.PRODUCT ?: "unknown")
            put("android_version", Build.VERSION.RELEASE ?: "unknown")
            put("sdk_int", Build.VERSION.SDK_INT)
            put("release_codename", Build.VERSION.CODENAME ?: "unknown")
            put("build_id", Build.ID ?: "unknown")
            put("fingerprint", Build.FINGERPRINT ?: "unknown")
            put("hardware", Build.HARDWARE ?: "unknown")
            put("board", Build.BOARD ?: "unknown")
            put("bootloader", Build.BOOTLOADER ?: "unknown")
            put("host", Build.HOST ?: "unknown")
            put("tags", Build.TAGS ?: "unknown")
            put("type", Build.TYPE ?: "unknown")
            put("supported_abis", Build.SUPPORTED_ABIS?.toList()?.let {
                org.json.JSONArray(it)
            } ?: org.json.JSONArray())
            put("is_emulator", isProbablyEmulator())

            // Phase 8 — Custom OEM ROM detection via SystemProperties reflection.
            // OemRomDetector is fully isolated: reflection errors are swallowed
            // internally and always produce OemResult.Stock here.
            when (val oemResult = OemRomDetector.detect()) {
                is OemRomDetector.OemResult.Custom -> {
                    put("custom_os", oemResult.name)
                    put("custom_os_version", oemResult.version)
                }
                OemRomDetector.OemResult.Stock -> {
                    put("custom_os", "stock")
                    put("custom_os_version", "stock")
                }
            }

            put("captured_at", utcFormat.format(Date()))
            put("captured_at_step", step)
        }
    }

    /**
     * Collect device info and write it to [Context.filesDir]/[FILE_NAME] on a
     * background IO coroutine. Fire-and-forget; call this from the permission
     * screen / permission-check step.
     *
     * @param context Any context (application context is used internally).
     * @param scope   [CoroutineScope] to launch the IO work on.
     * @param step    Label describing which step triggered capture
     *                (default: "permission_request").
     */
//    fun writeAsync(
//        context: Context,
//        scope: CoroutineScope,
//        step: String = "permission_request"
//    ) {
//        val appContext = context.applicationContext
//        scope.launch(Dispatchers.IO) {
//            try {
//                val json = collect(step)
//                val outFile = File(appContext.filesDir, FILE_NAME)
//                outFile.writeText(json.toString(2), Charsets.UTF_8)
//                Log.i(TAG, "Device info written → ${outFile.absolutePath}")
//            } catch (e: Exception) {
//                Log.e(TAG, "Failed to write device info: ${e.message}", e)
//            }
//        }
//    }
    // edit above b vicky writeasync
    fun writeAsync(
        context: Context,
        scope: CoroutineScope,
        step: String = "permission_request"
    ) {
        val appContext = context.applicationContext
        scope.launch(Dispatchers.IO) {
            try {
                val json = collect(step)

                // App-specific external storage:
                // /sdcard/Android/data/com.example.floatingassistant/files/device_info.json
                // No runtime permission required — this directory is always
                // writable by the app itself on API 19+.
                val externalDir = appContext.getExternalFilesDir(null)
                if (externalDir == null) {
                    Log.e(TAG, "External storage not available (unmounted?)")
                    return@launch
                }
                if (!externalDir.exists()) externalDir.mkdirs()

                val outFile = File(externalDir, FILE_NAME)
                outFile.writeText(json.toString(2), Charsets.UTF_8)
                Log.i(TAG, "Device info written → ${outFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write device info: ${e.message}", e)
            }
        }
    }


    /** Heuristic check for common emulator fingerprints/build values. */
    private fun isProbablyEmulator(): Boolean {
        return (Build.FINGERPRINT?.startsWith("generic") == true)
                || (Build.FINGERPRINT?.startsWith("unknown") == true)
                || (Build.MODEL?.contains("google_sdk") == true)
                || (Build.MODEL?.contains("Emulator") == true)
                || (Build.MODEL?.contains("Android SDK built for x86") == true)
                || (Build.MANUFACTURER?.contains("Genymotion") == true)
                || ((Build.BRAND?.startsWith("generic") == true) && (Build.DEVICE?.startsWith("generic") == true))
                || "google_sdk" == Build.PRODUCT
    }
}