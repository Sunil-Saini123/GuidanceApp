package com.example.floatingassistant

import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * DebugTelemetryClient - Live decision streamer for GuidanceApp.
 * Streams real-time state, decision milestones, and scroll mediator diffs
 * over USB (adb reverse tcp:3000 tcp:3000) or local Wi-Fi to the React Dashboard.
 *
 * Runs completely non-blocking on Dispatchers.IO. Fails silently if dashboard is offline.
 */
object DebugTelemetryClient {

    private const val TAG = "DebugTelemetry"
    private const val ENDPOINT = "http://127.0.0.1:3000/api/telemetry"
    private const val CONNECT_TIMEOUT_MS = 800
    private const val READ_TIMEOUT_MS = 1500

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    @Volatile var isEnabled: Boolean = true

    // [PERMANENT FIX] Do not remove this! Periodic heartbeat tells Dashboard the phone is alive.
    fun startHeartbeat() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            while (isActive && isEnabled) {
                emit(
                    type = "HEARTBEAT",
                    tag = "DeviceStatus",
                    title = "App Active Heartbeat",
                    description = "Phone connected and streaming telemetry"
                )
                delay(2000L)
            }
        }
    }

    fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    fun emit(
        type: String,
        tag: String,
        title: String,
        description: String,
        packageName: String? = null,
        screenTitle: String? = null,
        currentStep: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null,
        targetBounds: Rect? = null,
        elements: List<JSONObject>? = null,
        mediatorData: JSONObject? = null,
        aiData: JSONObject? = null,
        payload: Any? = null
    ) {
        if (!isEnabled) return

        scope.launch {
            try {
                val json = JSONObject()
                json.put("id", "${System.currentTimeMillis()}-${(1000..9999).random()}")
                json.put("type", type)
                json.put("tag", tag)
                json.put("title", title)
                json.put("description", description)
                json.put("timestamp", System.currentTimeMillis())

                if (packageName != null) json.put("packageName", packageName)
                if (screenTitle != null) json.put("screenTitle", screenTitle)
                if (currentStep != null) json.put("currentStep", currentStep)
                if (stepIndex != null) json.put("stepIndex", stepIndex)
                if (totalSteps != null) json.put("totalSteps", totalSteps)

                if (targetBounds != null) {
                    val b = JSONObject()
                    b.put("left", targetBounds.left)
                    b.put("top", targetBounds.top)
                    b.put("right", targetBounds.right)
                    b.put("bottom", targetBounds.bottom)
                    json.put("targetBounds", b)
                }

                if (elements != null && elements.isNotEmpty()) {
                    val arr = JSONArray()
                    elements.take(60).forEach { el ->
                        val item = JSONObject()
                        item.put("name", el.optString("name", ""))
                        item.put("is_clickable", el.optBoolean("is_clickable", false))
                        item.put("class_name", el.optString("class_name", ""))
                        item.put("resource_id", el.optString("resource_id", ""))
                        val bObj = el.optJSONObject("bounds")
                        if (bObj != null) {
                            val b = JSONObject()
                            val cx = bObj.optInt("center_x", 0)
                            val cy = bObj.optInt("center_y", 0)
                            val w = bObj.optInt("width", 0)
                            val h = bObj.optInt("height", 0)
                            b.put("left", cx - w / 2)
                            b.put("top", cy - h / 2)
                            b.put("right", cx + w / 2)
                            b.put("bottom", cy + h / 2)
                            item.put("bounds", b)
                        }
                        arr.put(item)
                    }
                    json.put("elements", arr)
                }

                if (mediatorData != null) json.put("mediatorData", mediatorData)
                if (aiData != null) json.put("aiData", aiData)
                if (payload != null) json.put("payload", payload)

                sendHttp(json.toString())
            } catch (_: Exception) {
                // Silently ignore telemetry exceptions — never impact main app!
            }
        }
    }

    private fun sendHttp(jsonBody: String) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL(ENDPOINT)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody)
                writer.flush()
            }

            conn.responseCode // execute
        } catch (_: Exception) {
            // Dashboard is offline or adb reverse not run yet — normal condition
        } finally {
            conn?.disconnect()
        }
    }

    // ── Pre-packaged Telemetry Shortcuts ─────────────────────────────────────

    fun sendScreenChanged(pkg: String, screen: String, elements: List<JSONObject>) {
        emit(
            type = "SCREEN_CHANGED",
            tag = "ScreenDetector",
            title = "Screen Transition: $screen",
            description = "Active window in $pkg with ${elements.size} elements",
            packageName = pkg,
            screenTitle = screen,
            elements = elements
        )
    }

    fun sendMediatorDiff(
        totalVisible: Int,
        previouslyKnown: Int,
        newAdded: List<String>,
        action: String
    ) {
        val data = JSONObject().apply {
            put("totalVisible", totalVisible)
            put("previouslyKnown", previouslyKnown)
            put("newAdded", JSONArray(newAdded))
            put("action", action)
        }
        val desc = when (action) {
            "SKIPPED_NO_CHANGE" -> "Scroll occurred but 0 new elements found. Database write avoided."
            else -> "Scroll revealed ${newAdded.size} new elements: ${newAdded.joinToString(", ")}"
        }

        emit(
            type = "MEDIATOR_DIFF",
            tag = "ScrollMediator",
            title = if (action == "SKIPPED_NO_CHANGE") "Mediator: No Changes (Skipped)" else "Mediator: +${newAdded.size} New Elements",
            description = desc,
            mediatorData = data
        )
    }

    fun sendElementFound(target: String, bounds: Rect) {
        emit(
            type = "ELEMENT_FOUND",
            tag = "MarkerFinder",
            title = "Target Located: '$target'",
            description = "Visual marker attached at coordinates [${bounds.left}, ${bounds.top}, ${bounds.right}, ${bounds.bottom}]",
            currentStep = target,
            targetBounds = bounds
        )
    }

    fun sendStepAdvanced(stepIndex: Int, totalSteps: Int, stepName: String, nextStep: String?) {
        emit(
            type = "STEP_ADVANCED",
            tag = "StateMachine",
            title = "Step ${stepIndex + 1}/$totalSteps Completed: '$stepName'",
            description = if (nextStep != null) "Advancing to next step: '$nextStep'" else "Final step reached!",
            currentStep = stepName,
            stepIndex = stepIndex,
            totalSteps = totalSteps
        )
    }

    fun sendWrongPath(tapped: String, expected: String) {
        emit(
            type = "WRONG_PATH",
            tag = "PathRecovery",
            title = "Diverged: Tapped '$tapped'",
            description = "Expected '$expected'. Displaying 'WRONG PATH' HUD and recovery guidance.",
            currentStep = expected
        )
    }

    fun sendIntentParsed(query: String, targetApp: String, screen: String, task: String) {
        emit(
            type = "INTENT_PARSED",
            tag = "CommandParser",
            title = "Intent Classified for \"$query\"",
            description = "App: $targetApp | Screen: $screen | Task: $task",
            payload = mapOf(
                "query" to query,
                "targetApp" to targetApp,
                "destinationScreen" to screen,
                "exactTask" to task
            )
        )
    }
}
