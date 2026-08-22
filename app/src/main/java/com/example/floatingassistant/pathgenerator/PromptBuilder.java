package com.example.floatingassistant.pathgenerator;

import org.json.JSONObject;

/**
 * PromptBuilder — Responsible for building structured LLM prompts specifically designed
 * for Android UI navigation path planning.
 */
public class PromptBuilder {

    public static final String SYSTEM_PROMPT =
            "You are an Android Navigation Assistant expert. Your task is to produce the exact sequence of UI screens and " +
            "menu items required for a user to accomplish an intended task on their Android device.\n\n" +
            "CRITICAL INSTRUCTIONS:\n" +
            "1. Output MUST be strict JSON only. Do NOT include markdown code blocks (```json) or extra text.\n" +
            "2. Required JSON format:\n" +
            "{\n" +
            "  \"destination\": \"<Final Target Screen or Option Name>\",\n" +
            "  \"path\": [\"<Step 1 / Start Screen>\", \"<Step 2 / Menu Item>\", \"<Step 3 / Destination>\"]\n" +
            "}\n" +
            "3. Keep screen and item names standard according to Android OS conventions (e.g., 'SettingsHomepage', 'Connected devices', 'Bluetooth', 'Display', 'Sound & vibration').\n" +
            "4. Ensure consecutive steps reflect realistic screen transitions.";

    public static String buildUserPrompt(PathRequest request) {
        StringBuilder sb = new StringBuilder();
        UserIntent intent = request.getIntent();

        sb.append("User Request Intent: ").append(intent.getIntentName()).append("\n");
        sb.append("User Query: \"").append(intent.getRawQuery()).append("\"\n");
        if (!intent.getTargetCategory().isEmpty()) {
            sb.append("Target Category: ").append(intent.getTargetCategory()).append("\n");
        }

        sb.append("\nCurrent Screen Position: ").append(request.getCurrentPosition()).append("\n");

        JSONObject devInfo = request.getDeviceInfo();
        if (devInfo != null && devInfo.length() > 0) {
            sb.append("\nDevice Context:\n");
            sb.append("- Manufacturer: ").append(devInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(devInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(devInfo.optString("android_version", "Unknown")).append("\n");
            String customOs = devInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS/ROM: ").append(customOs).append(" ").append(devInfo.optString("custom_os_version", "")).append("\n");
            }
        }

        JSONObject graph = request.getNavGraph();
        if (graph != null && graph.length() > 0) {
            sb.append("\nAvailable Nav Graph Context:\n").append(graph.toString()).append("\n");
        } else {
            sb.append("\nNav Graph Context: Standard Android Settings hierarchy applies.\n");
        }

        sb.append("\nGenerate the ordered navigation path array from current position to the intended target.");
        return sb.toString();
    }
}
