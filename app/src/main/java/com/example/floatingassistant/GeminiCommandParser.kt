package com.example.floatingassistant

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * GeminiCommandParser
 *
 * Sends the user's natural-language command to the Gemini 2.0 Flash model and
 * parses the response into a [ParsedCommand] containing:
 *   - [ParsedCommand.targetApp] — the mobile application implied by the command
 *   - [ParsedCommand.intent]    — the specific action to perform
 *
 * The model is instructed to ALWAYS respond in exactly two lines:
 * ```
 * Target App: <app>
 * Intent: <action>
 * ```
 *
 * Usage (from a coroutine):
 * ```kotlin
 * val result = GeminiCommandParser.parse("call mom")
 * // result?.targetApp == "Phone"
 * // result?.intent   == "Dial contact \"Mom\""
 * ```
 *
 * Returns `null` if:
 *   - The network call fails.
 *   - The model returns a non-conforming response.
 *   - The API key is missing or invalid (HTTP 400/403).
 */
object GeminiCommandParser {

    private const val TAG = "GeminiCommandParser"

    /**
     * How many times to retry a model on transient 503 / 429 / timeout before giving up.
     * 5 retries with 3 s base → waits: 3 s, 6 s, 12 s, 24 s (total ≈ 45 s).
     */
    private const val MAX_RETRIES = 5

    /** Base back-off delay in ms. Doubles on each attempt (exponential back-off). */
    private const val RETRY_DELAY_MS = 3_000L

    /** Thrown inside [callGeminiApi] for errors worth retrying (503 / 429 / timeout). */
    private class RetryableException(message: String) : Exception(message)
    private class QuotaExceededException(message: String) : Exception(message)

    /**
     * Result of a successful parse.
     * @param targetApp  The app identified by the AI (e.g. "WhatsApp", "Settings").
     * @param intent     The action to perform (e.g. "Send Message", "Enable dark mode").
     */
    data class ParsedCommand(val targetApp: String, val intent: String)

    /**
     * Models tried in order. Only models confirmed to exist for this API key version
     * are kept here — dead 404 models are removed to avoid wasting time.
     *
     * Based on observations:
     *   • gemini-3.6-flash — the only model available to new API keys (recommended
     *     by the deprecation messages of all other models). Retry aggressively when busy.
     */
    private val MODEL_FALLBACK_LIST = listOf(
        "gemini-3.6-flash",
        "gemini-3.7-flash",
        "gemini-3.8-flash",
        "gemini-3.5-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview"
    )

    private const val GEMINI_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models"

    private fun endpointUrl(model: String) = "$GEMINI_BASE/$model:generateContent"

    // ── System prompt — pinned instructions for every request ─────────────────
    private const val SYSTEM_PROMPT = """You are a mobile assistant that interprets user commands.

Given a user command, identify:
1. The mobile application the user wants to use (Target App)
2. The specific action they want to perform (Intent)

RULES:
- Respond in EXACTLY two lines, no more, no less.
- Line 1 must start with "Target App: "
- Line 2 must start with "Intent: "
- Be concise and specific.
- If the command mentions an app by name, use that exact app name.
- If no app is mentioned, infer the most likely app.
- Do NOT add explanations, greetings, or any other text.

EXAMPLES:
User: call mom
Target App: Phone
Intent: Dial contact "Mom"

User: send a WhatsApp message to john
Target App: WhatsApp
Intent: Send Message

User: play some music on spotify
Target App: Spotify
Intent: Play music

User: navigate to the airport in Maps
Target App: Maps
Intent: Directions to airport

User: set an alarm for 7am in Clock
Target App: Clock
Intent: Set alarm 7:00 AM

User: check my email in Gmail
Target App: Gmail
Intent: Check inbox

User: open camera and take a selfie
Target App: Camera
Intent: Take selfie

User: I want to turn on dark mode in mobile setting
Target App: Settings
Intent: Enable dark mode"""

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parses [userCommand] using the Gemini AI.
     *
     * For each model in [MODEL_FALLBACK_LIST]:
     *   • 503 / 429 / timeout → retry up to [MAX_RETRIES] times with exponential
     *     back-off (3 s → 6 s → 12 s → 24 s), calling [onProgress] before each wait
     *     so the UI can show live status.
     *   • 404               → skip to next model immediately.
     *   • other non-200     → skip to next model immediately.
     *
     * @param onProgress Optional suspend callback invoked with a human-readable status
     *                   string just before each retry delay. Runs on whatever dispatcher
     *                   the caller switches to inside the lambda.
     * @return [ParsedCommand] on success, `null` if all retries for all models fail.
     */
    suspend fun parse(
        userCommand: String,
        onProgress: suspend (String) -> Unit = {}
    ): ParsedCommand? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "YOUR_GEMINI_API_KEY_HERE") {
            Log.e(TAG, "Gemini API key is not set. Add GEMINI_API_KEY to local.properties.")
            return@withContext null
        }

        for (model in MODEL_FALLBACK_LIST) {
            Log.d(TAG, "Trying model: $model")
            var attempt = 0
            while (attempt < MAX_RETRIES) {
                try {
                    val responseText = callGeminiApi(apiKey, model, userCommand)
                    if (responseText != null) {
                        return@withContext parseResponse(responseText)
                    }
                    // null = non-retryable error → skip to next model
                    break
                } catch (e: QuotaExceededException) {
                    Log.w(TAG, "Model $model hit quota limit (429). Skipping to next model.")
                    break
                } catch (e: RetryableException) {
                    attempt++
                    if (attempt >= MAX_RETRIES) {
                        Log.w(TAG, "Model $model exhausted $MAX_RETRIES retries. Trying next model.")
                        break
                    }
                    val delayMs = RETRY_DELAY_MS * (1L shl (attempt - 1))  // 3s, 6s, 12s, 24s
                    val msg = "Server busy — retrying ($attempt/$MAX_RETRIES)…"
                    Log.w(TAG, "[$model] $msg Waiting ${delayMs / 1000}s")
                    onProgress(msg)
                    kotlinx.coroutines.delay(delayMs)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error with model $model: ${e.message}", e)
                    break
                }
            }
        }

        Log.e(TAG, "All models exhausted for: \"$userCommand\"")
        null
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Makes the HTTP POST to the Gemini REST API for the given [model].
     * @return The raw text content from the model, or `null` on non-retryable failure.
     * @throws RetryableException on HTTP 503 / 429 (server busy / rate-limited).
     */
    private fun callGeminiApi(apiKey: String, model: String, userCommand: String): String? {
        val url = URL("${endpointUrl(model)}?key=$apiKey")
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000   // gemini-3.6-flash can be slow; 45 s gives it room

            // Build request body
            val requestBody = buildRequestBody(userCommand)

            // Write request
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val httpCode = connection.responseCode
            Log.d(TAG, "[$model] HTTP $httpCode for: \"$userCommand\"")

            if (httpCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).readText()
                } ?: "(no error body)"
                Log.e(TAG, "API error $httpCode: $errorBody")

                if (httpCode == 429) {
                    throw QuotaExceededException("HTTP 429 — quota exceeded")
                }
                if (httpCode == 503) {
                    throw RetryableException("HTTP 503 — server busy")
                }
                return null
            }

            // Read response
            val responseBody = BufferedReader(
                InputStreamReader(connection.inputStream, Charsets.UTF_8)
            ).readText()

            extractTextFromResponse(responseBody)
        } catch (e: QuotaExceededException) {
            throw e
        } catch (e: RetryableException) {
            // Re-throw so the retry loop in parse() can handle it
            throw e
        } catch (e: SocketTimeoutException) {
            // Timeout is transient — treat as retryable so the same model is tried again
            Log.w(TAG, "[$model] SocketTimeoutException — will retry: ${e.message}")
            throw RetryableException("Timeout waiting for $model")
        } catch (e: Exception) {
            Log.e(TAG, "HTTP call failed for $model: ${e.message}", e)
            null
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds the JSON request body for the Gemini generateContent endpoint.
     *
     * We use a two-turn structure: a "user" turn carrying the system prompt
     * followed by a "model" acknowledgement, then the actual user command.
     * This works reliably on the free-tier REST API (which doesn't expose a
     * dedicated systemInstruction field on all model versions).
     */
    private fun buildRequestBody(userCommand: String): String {
        val root = JSONObject()

        // Combine system instructions + user command into the contents array
        val contents = JSONArray()

        // Turn 1 — system-like instruction as a user message
        val systemTurn = JSONObject()
        systemTurn.put("role", "user")
        val systemParts = JSONArray()
        systemParts.put(JSONObject().put("text", SYSTEM_PROMPT))
        systemTurn.put("parts", systemParts)
        contents.put(systemTurn)

        // Turn 2 — model acknowledges (keeps the conversation flowing)
        val modelAck = JSONObject()
        modelAck.put("role", "model")
        val ackParts = JSONArray()
        ackParts.put(JSONObject().put("text", "Understood. I will respond in exactly two lines: \"Target App: ...\" and \"Intent: ...\"."))
        modelAck.put("parts", ackParts)
        contents.put(modelAck)

        // Turn 3 — actual user command
        val userTurn = JSONObject()
        userTurn.put("role", "user")
        val userParts = JSONArray()
        userParts.put(JSONObject().put("text", userCommand))
        userTurn.put("parts", userParts)
        contents.put(userTurn)

        root.put("contents", contents)

        // Generation config — low temperature for deterministic, factual output.
        // No maxOutputTokens cap: the expected response is only ~2 lines (~15 tokens),
        // but capping was causing truncation mid-response.
        val genConfig = JSONObject()
        genConfig.put("temperature", 0.1)
        root.put("generationConfig", genConfig)

        return root.toString()
    }

    /**
     * Extracts the model's text response from the Gemini API JSON payload.
     */
    private fun extractTextFromResponse(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) {
                Log.w(TAG, "No candidates in response")
                return null
            }
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts = content.getJSONArray("parts")
            if (parts.length() == 0) {
                Log.w(TAG, "No parts in first candidate")
                return null
            }
            parts.getJSONObject(0).getString("text").trim()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to extract text from response: ${e.message}")
            null
        }
    }

    /**
     * Parses the model's text response into a [ParsedCommand].
     *
     * Primary format (strict two lines):
     * ```
     * Target App: Settings
     * Intent: Enable dark mode
     * ```
     *
     * Resilient fallback: if the value after ":" is blank, the parser looks at
     * the NEXT non-blank line as the value. This handles models that occasionally
     * split the label and value across two lines.
     *
     * @return [ParsedCommand] or `null` if the format cannot be recognised at all.
     */
    private fun parseResponse(rawText: String): ParsedCommand? {
        // Normalise line endings and split into non-blank lines
        val lines = rawText
            .replace("\r\n", "\n")
            .replace("\r", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }

        Log.d(TAG, "Model response lines: $lines")
        Log.d(TAG, "Raw text: \"$rawText\"")

        /**
         * Extract the value for a label like "Target App:" or "Intent:".
         * If the value on the same line is empty, returns the next line's content.
         */
        fun extractValue(prefix: String): String? {
            val idx = lines.indexOfFirst { it.startsWith(prefix, ignoreCase = true) }
            if (idx < 0) return null
            val sameLine = lines[idx].substringAfter(":").trim()
            if (sameLine.isNotEmpty()) return sameLine
            // Value was on the next line (model split label and value)
            val nextLine = lines.getOrNull(idx + 1)
                ?.takeIf { !it.startsWith("Target App:", ignoreCase = true) &&
                           !it.startsWith("Intent:", ignoreCase = true) }
                ?.trim()
            return if (!nextLine.isNullOrEmpty()) nextLine else null
        }

        val targetApp = extractValue("Target App:")
        val intent    = extractValue("Intent:")

        if (targetApp.isNullOrEmpty() || intent.isNullOrEmpty()) {
            Log.w(TAG, "Could not extract targetApp or intent. Lines: $lines")
            return null
        }

        Log.i(TAG, "Parsed → targetApp=\"$targetApp\", intent=\"$intent\"")
        return ParsedCommand(targetApp = targetApp, intent = intent)
    }
}
