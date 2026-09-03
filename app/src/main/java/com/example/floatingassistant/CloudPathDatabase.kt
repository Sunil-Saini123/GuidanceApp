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
 * `Manufacturer_Brand_AndroidVersion`, e.g. "samsung_samsung_14".
 *
 * Firestore layout:
 * ```
 * device_paths (collection)
 *   └─ <signature> (document, one per unique device signature)
 *        entries: {
 *          "whatsapp": {                                        ← app key
 *            "change_profile_picture": "WhatsApp -> 3 dots -> Settings -> Profile -> Change Profile",
 *            ...
 *          },
 *          "settings": { ... },
 *          ...
 *        }
 * ```
 * `entries` is a two-level map: sanitized app key → { sanitized task key → path string }.
 * Lookup path: `entries.<appKey>.<taskKey>`. Both keys are lowercased + sanitized.
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
        val androidVersion: String
    )

    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    // ── Public API ───────────────────────────────────────────────────────────

    /**
     * Look up a stored path for the given [targetApp] and [exactTask] on [deviceInfo].
     *
     * Firestore path: `entries.<appKey>.<taskKey>`
     *   - appKey  = sanitizeIntentKey(targetApp)   e.g. "whatsapp"
     *   - taskKey = sanitizeIntentKey(exactTask)   e.g. "change_profile_picture"
     *
     * @return the stored path string on a hit, or "" on any miss / error.
     */
    suspend fun lookup(
        targetApp: String,
        exactTask: String,
        deviceInfo: DeviceSignatureInfo = currentDeviceSignatureInfo()
    ): String {
        val signature = buildSignature(deviceInfo)
        val appKey    = sanitizeIntentKey(targetApp)
        val taskKey   = sanitizeIntentKey(exactTask)
        val docRef    = firestore.collection(COLLECTION).document(signature)

        Log.d(TAG, "Tier 2 Firestore lookup — signature=$signature, app=$appKey, task=$taskKey")

        val snapshot = try {
            docRef.get().await()
        } catch (e: Exception) {
            Log.e(TAG, "Firestore read failed for signature=$signature: ${e.message}", e)
            return ""
        }

        if (!snapshot.exists()) {
            Log.w(TAG, "Tier 2 Firestore: Miss — no document for signature=$signature")
            return ""
        }

        // Level 1: check app sub-map exists
        @Suppress("UNCHECKED_CAST")
        val appMap = snapshot.get("entries.$appKey") as? Map<String, Any>
        if (appMap == null) {
            Log.w(TAG, "Tier 2 Firestore: Miss — no app entry for appKey=$appKey (signature=$signature)")
            return ""
        }

        // Level 2: check task key inside the app sub-map
        val path = appMap[taskKey] as? String
        if (path.isNullOrEmpty()) {
            Log.w(TAG, "Tier 2 Firestore: Miss — no task entry for taskKey=$taskKey (app=$appKey, signature=$signature)")
            return ""
        }

        Log.i(TAG, "Tier 2 Firestore: Match Found — \"$targetApp / $exactTask\" → \"$path\" (signature=$signature)")
        return path
    }

    /**
     * Stores [path] under the two-level key derived from [targetApp] and [exactTask] for
     * [deviceInfo]'s signature.
     *
     * Firestore path written: `entries.<appKey>.<taskKey>`
     *
     * Uses `update()` with a dotted field path to surgically write only this single
     * leaf — sibling apps and sibling tasks are never touched. Falls back to `set(merge)`
     * if the document doesn't exist yet.
     *
     * Not yet called from any active pipeline (wired in Phase 4 after Groq generates a path).
     */
    suspend fun addEntry(
        targetApp: String,
        exactTask: String,
        path: String,
        deviceInfo: DeviceSignatureInfo = currentDeviceSignatureInfo()
    ) {
        val signature = buildSignature(deviceInfo)
        val appKey    = sanitizeIntentKey(targetApp)
        val taskKey   = sanitizeIntentKey(exactTask)
        val fieldPath = "entries.$appKey.$taskKey"
        val docRef    = firestore.collection(COLLECTION).document(signature)

        try {
            // Try update() first (doc exists) — only touches this single leaf key.
            docRef.update(fieldPath, path).await()
            Log.i(TAG, "Entry updated — signature=$signature, $fieldPath → \"$path\"")
        } catch (e: Exception) {
            // update() fails if document doesn't exist yet → fall back to set(merge).
            try {
                val data = mapOf(
                    "entries" to mapOf(appKey to mapOf(taskKey to path))
                )
                docRef.set(data, SetOptions.merge()).await()
                Log.i(TAG, "Entry created (set/merge) — signature=$signature, $fieldPath → \"$path\"")
            } catch (e2: Exception) {
                Log.e(TAG, "Failed to add entry — signature=$signature, $fieldPath: ${e2.message}", e2)
            }
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

    /** Builds signature info from live Build.* sources. */
    fun currentDeviceSignatureInfo(): DeviceSignatureInfo {
        val manufacturer = Build.MANUFACTURER ?: "unknown"
        val brand = Build.BRAND ?: "unknown"
        val androidVersion = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() }
            ?: Build.VERSION.SDK_INT.toString()
        return DeviceSignatureInfo(manufacturer, brand, androidVersion)
    }

    /**
     * signature = "<manufacturer>_<brand>_<android_version>",
     * lowercased and sanitized to Firestore-safe document-ID characters.
     */
    fun buildSignature(info: DeviceSignatureInfo): String {
        val raw = listOf(
            info.manufacturer,
            info.brand,
            info.androidVersion
        ).joinToString("_")

        return raw.lowercase(Locale.US)
            .replace(Regex("[^a-z0-9_]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "unknown_device" }
            .take(400)
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