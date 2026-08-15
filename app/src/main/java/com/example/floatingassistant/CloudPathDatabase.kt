package com.example.floatingassistant

import android.os.Build
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.tasks.await
import java.util.Locale

/**
 * CloudPathDatabase — Firestore-backed "has anyone done this before?" lookup.
 *
 * Mirrors [PathDatabase]'s contract but is backed by Cloud Firestore instead
 * of a local JSON file, so a path discovered on one device+ROM combination
 * becomes available to every other user on that *exact same* combination.
 *
 * ── Matching strategy: EXACT intent match ──────────────────────────────────
 * The caller is expected to run the raw user query through an intent-
 * extraction model *before* calling [lookup] / [addEntry], so that
 * differently-worded queries for the same underlying task ("change my dp",
 * "update whatsapp profile photo") collapse to the same canonical intent
 * string upstream. Given that, this class does a plain exact-match lookup —
 * no keyword-overlap scoring, no fuzziness. If the canonical intent isn't
 * present for this device signature, it returns "".
 *
 * The only normalization applied here is `trim().lowercase()` on the intent
 * string before it's used as the match key — a safety margin against
 * incidental formatting noise (trailing space, casing) from the intent
 * model, not a reintroduction of fuzzy matching.
 *
 * ── Device signature ────────────────────────────────────────────────────
 * `Manufacturer_Brand_Model_AndroidVersion_Os`, where `Os` is the OEM skin
 * name+version combined (e.g. "one_ui6_0") rather than just the skin name —
 * two phones can share an Android version and skin name but differ in skin
 * version (One UI 5.1 vs 6.0), with genuinely different menu layouts, so the
 * version stays folded into that last segment.
 *
 * Firestore layout:
 * ```
 * device_paths (collection)
 *   └─ <signature> (document, one per unique device signature)
 *        entries: {
 *          "change_whatsapp_profile_picture": "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile",
 *          ...
 *        }
 * ```
 * `entries` is a flat map: sanitized intent key → path string directly. No
 * per-entry metadata — the key/path pair is all a lookup needs.
 *
 * All calls are suspend functions — run them from a coroutine (they do not
 * block a thread while waiting on network I/O, so Dispatchers.IO is optional
 * but harmless).
 *
 * Requires [ensureSignedIn] to have completed at least once (see setup notes
 * in the accompanying guide) before [lookup] / [addEntry] are called, since
 * Firestore security rules require an authenticated (anonymous) user.
 */
object CloudPathDatabase {

    private const val TAG = "CloudPathDatabase"
    private const val COLLECTION = "device_paths"

    data class DeviceSignatureInfo(
        val manufacturer: String,
        val brand: String,
        val model: String,
        val androidVersion: String,
        val customOs: String,
        val customOsVersion: String
    )

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Resolve [intent] (already normalized by your intent-extraction model)
     * against Firestore for [deviceInfo] (defaults to the current device's
     * live signature, built the same way [DeviceInfoWriter] builds its
     * snapshot).
     *
     * @return the stored path string if this exact intent is present for
     *         this exact device signature, otherwise "".
     */
    suspend fun lookup(
        intent: String,
        deviceInfo: DeviceSignatureInfo = currentDeviceSignatureInfo()
    ): String {
        val signature = buildSignature(deviceInfo)
        val key = sanitizeIntentKey(intent)
        val docRef = firestore.collection(COLLECTION).document(signature)

        val snapshot = try {
            docRef.get().await()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore read failed for signature=$signature: ${e.message}", e)
            return ""
        }

        // "Has anyone done this before?" (on THIS exact device signature)
        if (!snapshot.exists()) {
            Log.w(TAG, "Path not found — no document for signature=$signature")
            return ""
        }

        // "Is the query present?" — direct field lookup on entries.<key>, exact match only.
        val path = snapshot.get("entries.$key") as? String

        if (path.isNullOrEmpty()) {
            Log.w(TAG, "Path not found — no entry for intent=\"$intent\" (key=$key, signature=$signature)")
            return ""
        }

        Log.i(TAG, "Resolved \"$intent\" → \"$path\" (signature=$signature)")
        return path
    }

    /**
     * Stores [path] under the exact-match key derived from [intent] for
     * [deviceInfo]'s signature. Uses a dotted-path [DocumentReference.update]
     * targeting `entries.<key>` so sibling entries are never touched —
     * `set(..., merge = true)` would NOT be safe here, since Firestore's
     * merge only preserves *sibling top-level fields*; a nested map field
     * (like `entries`) passed to a merge-set is replaced wholesale, not
     * merged key-by-key.
     *
     * Not wired into any UI flow yet (per the "store after a successful,
     * previously-unmapped task" step, which is future work) — but the
     * schema and this function are ready for it.
     */
    suspend fun addEntry(
        intent: String,
        path: String,
        deviceInfo: DeviceSignatureInfo = currentDeviceSignatureInfo()
    ) {
        val signature = buildSignature(deviceInfo)
        val key = sanitizeIntentKey(intent)
        val docRef = firestore.collection(COLLECTION).document(signature)

        try {
            // Use SetOptions.mergeFields to perform a deep merge on the 'entries' map.
            // This creates the document if it doesn't exist AND updates only the 
            // specific nested key 'entries.<key>' without overwriting other entries.
            val data = mapOf(
                "entries" to mapOf(key to path)
            )

            docRef.set(
                data,
                SetOptions.mergeFields("entries.$key")
            ).await()

            Log.i(TAG, "Entry added for signature=$signature, key=$key → \"$path\"")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add entry for signature=$signature, key=$key: ${e.message}", e)
        }
    }

    /**
     * Signs the device in anonymously if not already signed in. Call this
     * once at process start (e.g. Application.onCreate or MainActivity's
     * first onCreate) — [lookup] and [addEntry] will fail Firestore's
     * security rules until this has succeeded at least once.
     */
    suspend fun ensureSignedIn() {
        if (FirebaseAuth.getInstance().currentUser != null) return
        try {
            FirebaseAuth.getInstance().signInAnonymously().await()
            Log.i(TAG, "Signed in anonymously: uid=${FirebaseAuth.getInstance().currentUser?.uid}")
        } catch (e: Exception) {
            Log.e(TAG, "Anonymous sign-in failed: ${e.message}", e)
        }
    }

    // ── Device signature ────────────────────────────────────────────────────

    /** Builds signature info from live Build.* + OemRomDetector, same sources DeviceInfoWriter uses. */
    fun currentDeviceSignatureInfo(): DeviceSignatureInfo {
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val brand = Build.BRAND ?: "unknown"
        val model = Build.MODEL ?: "unknown"
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        val (customOs, customOsVersion) = when (val result = OemRomDetector.detect()) {
            is OemRomDetector.OemResult.Custom -> result.name to result.version
            OemRomDetector.OemResult.Stock -> "stock" to "stock"
        }
        return DeviceSignatureInfo(manufacturer, brand, model, androidVersion, customOs, customOsVersion)
    }

    /** The "Os" segment: OEM skin name + version combined, e.g. "one_ui_6_0" or "stock". */
    private fun osSegment(info: DeviceSignatureInfo): String =
        "${info.customOs}_${info.customOsVersion}"

    /**
     * signature = "<manufacturer>_<brand>_<model>_<android_version>_<os>",
     * lowercased and sanitized to Firestore-safe document-ID characters
     * (Firestore doc IDs must not contain "/", must not be "." or "..",
     * and must not be empty — sanitizing here also collapses whitespace and
     * punctuation from OEM strings like "One UI" or "V816.0.6.0").
     */
    fun buildSignature(info: DeviceSignatureInfo): String {
        val raw = listOf(
            info.manufacturer,
            info.brand,
            info.model,
            info.androidVersion,
            osSegment(info)
        ).joinToString("_")

        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "unknown_device" }
            .take(400) // Firestore doc IDs allow up to 1500 bytes; well under that
    }

    /**
     * Sanitizes an already-canonical intent string into a Firestore-safe map
     * key: lowercased, trimmed, non [a-z0-9_] characters collapsed to "_".
     * Firestore field names must not contain "." or start with "__" — this
     * also strips a leading run of underscores to avoid the reserved prefix.
     */
    internal fun sanitizeIntentKey(intent: String): String {
        val cleaned = intent.trim().lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
        val noReservedPrefix = cleaned.trimStart('_').ifBlank { "intent" }
        return noReservedPrefix.take(400)
    }
}