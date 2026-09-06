package com.example.floatingassistant.pathgenerator

import android.os.Build
import android.util.Log
import org.json.JSONObject

class GroqHealer {
    
    companion object {
        private const val TAG = "[NavigationEngine]"
        private const val NET_TAG = "[NetworkClient]"

        suspend fun requestHealing(
            context: android.content.Context,
            targetPackage: String,
            initialIntent: String,
            failedSequence: List<String>,
            cleanPageJsonStr: String
        ): List<String>? {
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val client = GroqProxyClient()
                    
                    val sysPrompt = "You are an AI healing agent for an Android app. The user tried to execute an intent but got stuck. Provide a revised sub-path of steps (just action labels like 'Settings', 'Chats') to reach the goal. Respond with JSON: {\"path\": [\"step1\", \"step2\"]}. If you need the user to go back, output [\"BACK\"]."
                    val userPrompt = """
                        Device: ${Build.MANUFACTURER} ${Build.MODEL} (API ${Build.VERSION.SDK_INT})
                        Intent: $initialIntent
                        Failed Sequence: ${failedSequence.joinToString(" -> ")}
                        Current Screen JSON: ${cleanPageJsonStr.take(GroqProxyClient.MAX_SCREEN_CONTENT_CHARS)}
                    """.trimIndent()
                    
                    Log.i(NET_TAG, "Sending Groq Healing Request -> Intent: $initialIntent | Failed: ${failedSequence.joinToString("->")}")
                    
                    val rawResp = client.sendDirectRequest(sysPrompt, userPrompt)
                    Log.i(NET_TAG, "Groq Healing Response: $rawResp")
                    
                    val navPath = GroqResponseParser.parse(rawResp)
                    val steps = navPath?.steps
                    
                    Log.i(TAG, "Groq Healing Decision: $steps")
                    
                    steps
                } catch (e: Exception) {
                    Log.e(NET_TAG, "Groq Healing failed: ${e.message}")
                    null
                }
            }
        }
    }
}
