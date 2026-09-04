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
     * Structured intent payload produced by a successful Gemini parse.
     *
     * @param targetApp         The application to open, e.g. "WhatsApp", "Settings".
     * @param destinationScreen The screen/section inside the app to reach,
     *                          e.g. "Profile", "Wi-Fi Settings", "Dark Mode".
     * @param exactTask         The specific action to perform once at the destination,
     *                          e.g. "Change profile picture", "Toggle dark mode switch".
     */
    data class ParsedCommand(
        val targetApp:         String,
        val destinationScreen: String,
        val exactTask:         String
    )


    /**
     * Models tried in order. Only models confirmed to exist for this API key version
     * are kept here — dead 404 models are removed to avoid wasting time.
     *
     * Based on observations:
     *   • gemini-3.6-flash — the only model available to new API keys (recommended
     *     by the deprecation messages of all other models). Retry aggressively when busy.
     */
    private val MODEL_FALLBACK_LIST = listOf(
        "gemini-3.7-flash",
        "gemini-3.6-flash",
        "gemini-3.5-flash",
        "gemini-3.8-flash",
        "gemini-flash-latest",
        "gemini-3.1-pro-preview"
    )

    private val DEFAULT_GEMINI_KEY = BuildConfig.GEMINI_API_KEY

    private const val GEMINI_BASE =
        "https://generativelanguage.googleapis.com/v1beta/models"

    private fun endpointUrl(model: String) = "$GEMINI_BASE/$model:generateContent"

    // ── System prompt — pinned instructions for every request ─────────────────
    private const val SYSTEM_PROMPT = """You are a mobile navigation assistant that interprets user commands.

Given a user command, identify three things:
1. app       — the mobile application to open.
2. end_page  — the specific screen, section, or menu to navigate to inside the app.
3. task      — the precise action to perform once at the destination.

CRITICAL NAMING RULES (for consistency across queries):
- You MUST use a consistent, generalized naming scheme for all outputs. Use lowercase and snake_case for all values.
- `app`: Use the canonical lowercase name. Normalize generic names to canonical apps (e.g., "email" or "mail" -> "gmail", "browser" or "web" -> "chrome").
- `end_page`: Use a standardized page name (e.g., "alarm_tab", "profile_page", "display_settings").
- `task`: You MUST group similar requests into exactly ONE generalized canonical task name. 
  For example, "set a new alarm", "add an alarm", "create alarm" MUST all resolve to "create_alarm". 
- `task` plurals: ALWAYS use singular nouns for tasks, NEVER plurals (e.g., use "search_video" instead of "search_videos", "read_email" instead of "read_emails").

OUTPUT FORMAT RULES:
- Respond in EXACTLY three lines, no more, no less.
- Line 1 must start with "app: "
- Line 2 must start with "end_page: "
- Line 3 must start with "task: "
- Do NOT add explanations, greetings, bullets, or any other text.

EXAMPLES:
User: change my profile picture on WhatsApp
app: whatsapp
end_page: profile_page
task: update_profile_picture

User: search for funny cats on youtube
app: youtube
end_page: search_page
task: search_video

User: search videos of dogs
app: youtube
end_page: search_page
task: search_video

User: check my mail
app: gmail
end_page: inbox_page
task: read_email

User: send an email to boss
app: gmail
end_page: compose_page
task: compose_email

User: set an alarm for 7am in Clock
app: clock
end_page: alarm_tab
task: create_alarm

User: take a selfie
app: camera
end_page: camera_page
task: take_selfie"""



    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Parses [userCommand] into a structured [ParsedCommand].
     *
     * Architecture:
     * 1. First tries direct Google Gemini REST API using the configured Gemini key.
     * 2. If Gemini API is unreachable, falls back to the local offline IntentClassificationEngine.
     *
     * Guaranteed to return a valid [ParsedCommand].
     */
    suspend fun parse(
        userCommand: String,
        onProgress: suspend (String) -> Unit = {}
    ): ParsedCommand = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() && it != "YOUR_GEMINI_API_KEY_HERE" }
            ?: DEFAULT_GEMINI_KEY

        // ── 1. Direct Gemini REST API (Primary intent classification) ─────────
        if (apiKey.isNotBlank()) {
            Log.d(TAG, "Attempting direct Gemini REST API for intent classification")
            onProgress("Analyzing intent with Gemini…")
            for (model in MODEL_FALLBACK_LIST) {
                var attempt = 0
                while (attempt < MAX_RETRIES) {
                    try {
                        val responseText = callGeminiApi(apiKey, model, userCommand)
                        if (responseText != null) {
                            val parsed = parseResponse(responseText)
                            if (parsed != null) {
                                Log.i(TAG, "Gemini intent classification succeeded with $model: $parsed")
                                return@withContext parsed
                            }
                        }
                        break
                    } catch (e: QuotaExceededException) {
                        Log.w(TAG, "Model $model hit quota limit (429). Skipping.")
                        break
                    } catch (e: RetryableException) {
                        attempt++
                        if (attempt >= MAX_RETRIES) break
                        val delayMs = RETRY_DELAY_MS * (1L shl (attempt - 1))
                        onProgress("Gemini busy — retrying…")
                        kotlinx.coroutines.delay(delayMs)
                    } catch (e: Exception) {
                        Log.w(TAG, "Model $model failed: ${e.message}")
                        break
                    }
                }
            }
        }

        // ── 2. Offline Local Intent Classification Fallback ───────────────────
        Log.i(TAG, "Falling back to offline IntentClassificationEngine")
        onProgress("Resolving intent locally…")
        val localParsed = resolveLocally(userCommand)
        Log.i("[PathFinder]", "Parsed Intent (Offline Fallback) -> App: ${localParsed.targetApp}, Screen: ${localParsed.destinationScreen}, Task: ${localParsed.exactTask}")
        localParsed
    }

    private const val PROXY_URL = "https://navigation-app-server.vercel.app/api/navigate"

    /**
     * Calls our deployed proxy server (does not require any device-side API key).
     */
    private fun callProxyApi(userCommand: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(PROXY_URL)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 8_000
            connection.readTimeout = 15_000

            val requestBody = JSONObject().apply {
                put("model", "llama-3.3-70b-versatile")
                put("temperature", 0.0)
                put("max_tokens", 250)

                val messages = JSONArray()
                messages.put(JSONObject().apply {
                    put("role", "system")
                    put("content", SYSTEM_PROMPT)
                })
                messages.put(JSONObject().apply {
                    put("role", "user")
                    put("content", userCommand)
                })
                put("messages", messages)
            }

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody.toString())
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()

            if (statusCode in 200..299) {
                val root = JSONObject(responseText)
                if (root.has("choices")) {
                    val choices = root.getJSONArray("choices")
                    if (choices.length() > 0) {
                        val message = choices.getJSONObject(0).optJSONObject("message")
                        return message?.optString("content", "")
                    }
                }
                responseText
            } else {
                Log.w(TAG, "Proxy returned HTTP $statusCode: $responseText")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Proxy call failed: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Resolves intent locally using offline IntentClassificationEngine and IntentProvider.
     */
    fun resolveLocally(userCommand: String): ParsedCommand {
        val query = userCommand.trim()
        val queryLower = query.lowercase(java.util.Locale.US)

        // 1. Try local IntentClassificationEngine
        try {
            val engine = com.example.floatingassistant.intent.IntentClassificationEngine()
            val match = engine.classify(query)
            if (match.isConfident && match.userIntent != null) {
                return mapUserIntentToCommand(match.userIntent, query)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Local IntentClassificationEngine failed: ${e.message}")
        }

        // 2. Try IntentProvider predefined intents
        val predefined = com.example.floatingassistant.pathgenerator.IntentProvider.findMatchingIntent(query)
        if (predefined.intentName != "UNKNOWN" && predefined.intentName != "GENERIC_NAVIGATE") {
            return mapUserIntentToCommand(predefined, query)
        }

        // 3. Fallback heuristics for common apps
        return when {
            queryLower.contains("whatsapp") -> {
                val feature = query.replace(Regex("(?i)\\b(open|go to|change|set|show|view|my|whatsapp|in whatsapp|on whatsapp)\\b"), "").trim()
                val screen = when {
                    queryLower.contains("profile") || queryLower.contains("dp") -> "Profile"
                    feature.isNotBlank() -> feature
                    else -> "Chats"
                }
                ParsedCommand(targetApp = "WhatsApp", destinationScreen = screen, exactTask = query)
            }
            queryLower.contains("youtube") -> {
                ParsedCommand(targetApp = "YouTube", destinationScreen = "Home", exactTask = query)
            }
            queryLower.contains("call") || queryLower.contains("dial") || queryLower.contains("phone") -> {
                ParsedCommand(targetApp = "Phone", destinationScreen = "Dialer", exactTask = query)
            }
            queryLower.contains("camera") || queryLower.contains("photo") || queryLower.contains("selfie") -> {
                ParsedCommand(targetApp = "Camera", destinationScreen = "Camera", exactTask = query)
            }
            queryLower.contains("map") || queryLower.contains("direction") || queryLower.contains("navigate to") -> {
                ParsedCommand(targetApp = "Maps", destinationScreen = "Search", exactTask = query)
            }
            queryLower.contains("wifi") || queryLower.contains("wi-fi") -> {
                ParsedCommand(targetApp = "Settings", destinationScreen = "Wi-Fi", exactTask = query)
            }
            queryLower.contains("bluetooth") -> {
                ParsedCommand(targetApp = "Settings", destinationScreen = "Bluetooth", exactTask = query)
            }
            queryLower.contains("display") || queryLower.contains("brightness") || queryLower.contains("dark mode") -> {
                ParsedCommand(targetApp = "Settings", destinationScreen = "Display", exactTask = query)
            }
            queryLower.contains("battery") -> {
                ParsedCommand(targetApp = "Settings", destinationScreen = "Battery saver", exactTask = query)
            }
            queryLower.contains("sound") || queryLower.contains("volume") -> {
                ParsedCommand(targetApp = "Settings", destinationScreen = "Sound & vibration", exactTask = query)
            }
            else -> {
                val feature = query.replace(Regex("^(open|go to|change|set|turn on|turn off|show|view)\\s+", RegexOption.IGNORE_CASE), "").trim()
                val dest = if (feature.isNotBlank()) feature else "Settings"
                ParsedCommand(targetApp = "Settings", destinationScreen = dest, exactTask = query)
            }
        }
    }

    private fun mapUserIntentToCommand(intent: com.example.floatingassistant.pathgenerator.UserIntent, rawQuery: String): ParsedCommand {
        return when (intent.intentName.uppercase(java.util.Locale.US)) {
            "ENABLE_BLUETOOTH" -> ParsedCommand("Settings", "Bluetooth", "Enable Bluetooth")
            "OPEN_WIFI_SETTINGS", "CONNECT_WIFI" -> ParsedCommand("Settings", "Wi-Fi", "Open Wi-Fi settings")
            "OPEN_DISPLAY_SETTINGS" -> ParsedCommand("Settings", "Display", "Open display settings")
            "OPEN_SECURITY_PRIVACY" -> ParsedCommand("Settings", "Security & privacy", "Open security settings")
            "CHANGE_WALLPAPER" -> ParsedCommand("Settings", "Wallpaper & style", "Change wallpaper")
            "OPEN_BATTERY_SAVER" -> ParsedCommand("Settings", "Battery saver", "Turn on battery saver")
            "OPEN_ACCESSIBILITY_SETTINGS" -> ParsedCommand("Settings", "Accessibility", "Open accessibility settings")
            "OPEN_SOUND_SETTINGS" -> ParsedCommand("Settings", "Sound & vibration", "Adjust sound settings")
            else -> ParsedCommand(intent.targetCategory.ifEmpty { "Settings" }, intent.intentName, rawQuery)
        }
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
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.doOutput = true
            connection.connectTimeout = 15_000
            connection.readTimeout = 45_000   // gemini-flash can be slow; 45 s gives it room

            // Build request body
            val requestBody = buildRequestBody(userCommand)

            // Write request
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(requestBody)
                writer.flush()
            }

            val httpCode = connection.responseCode
            if (BuildConfig.DEBUG) Log.d(TAG, "[$model] HTTP $httpCode")

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
        ackParts.put(JSONObject().put("text",
            "Understood. I will respond in exactly three lines: " +
            "\"app: ...\", \"end_page: ...\", and \"task: ...\" using standardized snake_case naming."
        ))
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
            val root       = JSONObject(responseBody)
            val candidates = root.getJSONArray("candidates")
            if (candidates.length() == 0) {
                Log.w(TAG, "No candidates in response")
                return null
            }
            val content = candidates.getJSONObject(0).getJSONObject("content")
            val parts   = content.getJSONArray("parts")
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
     * Expected format (strict three lines):
     * ```
     * app: whatsapp
     * end_page: profile_page
     * task: update_profile_picture
     * ```
     *
     * Resilient fallback: if the value after ":" is blank on a given line, the
     * parser looks at the NEXT non-blank line as the value.  This handles models
     * that occasionally split the label and value across two lines.
     *
     * @return [ParsedCommand] or `null` if the format cannot be recognised at all.
     */
    private fun parseResponse(rawText: String): ParsedCommand? {
        // Normalise line endings and split into non-blank lines
        val lines = rawText
            .replace("\r\n", "\n")
            .replace("\r",   "\n")
            .split("\n")
            .map    { it.trim() }
            .filter { it.isNotBlank() }

        if (BuildConfig.DEBUG) Log.d(TAG, "Model response lines: $lines")

        // Labels we recognise — checked case-insensitively
        val ALL_LABELS = listOf("app:", "end_page:", "task:")

        /**
         * Extract the value for a given label prefix.
         * If the value on the same line is empty, returns the next line's content
         * provided it doesn't itself look like another label.
         */
        fun extractValue(prefix: String): String? {
            val idx = lines.indexOfFirst { it.startsWith(prefix, ignoreCase = true) }
            if (idx < 0) return null
            val sameLine = lines[idx].substringAfter(":").trim()
            if (sameLine.isNotEmpty()) return sameLine
            // Value split to next line (model formatting quirk)
            val nextLine = lines.getOrNull(idx + 1)
                ?.takeIf { candidate ->
                    ALL_LABELS.none { label ->
                        candidate.startsWith(label, ignoreCase = true)
                    }
                }
                ?.trim()
            return if (!nextLine.isNullOrEmpty()) nextLine else null
        }

        val targetApp         = extractValue("app:")
        val destinationScreen = extractValue("end_page:")
        val exactTask         = extractValue("task:")

        // All three fields are required
        if (targetApp.isNullOrEmpty() || destinationScreen.isNullOrEmpty() || exactTask.isNullOrEmpty()) {
            Log.w(TAG, "[PathFinder] Gemini response incomplete — " +
                "targetApp=$targetApp, destinationScreen=$destinationScreen, exactTask=$exactTask. " +
                "Raw lines: $lines")
            return null
        }

        // ── [PathFinder] structured log ───────────────────────────────────────
        Log.i("[PathFinder]",
            "Parsed Intent -> App: $targetApp, Screen: $destinationScreen, Task: $exactTask")

        return ParsedCommand(
            targetApp         = targetApp,
            destinationScreen = destinationScreen,
            exactTask         = exactTask
        )
    }
}
