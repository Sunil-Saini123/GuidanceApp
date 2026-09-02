package com.example.floatingassistant.pathgenerator;

import org.json.JSONObject;

import java.util.Map;

/**
 * PromptBuilder — Responsible for building structured LLM prompts specifically designed
 * for Android UI navigation path planning. Packages candidate intent, extracted parameters,
 * device/OEM context, and navigation graph context with verification instructions for Groq.
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
            "3. Keep screen and item names accurate according to the specific device manufacturer, OEM ROM (One UI, MIUI, ColorOS, realme UI), and Android version.\n" +
            "4. Prefer paths supported by the provided navigation graph / Android system hierarchy. Do not invent arbitrary screen names.\n" +
            "5. Verify that each consecutive navigation step is realistically reachable from the previous step.\n" +
            "6. Preserve user extracted parameters where applicable (e.g. target network name or value).\n" +
            "7. If the available information is insufficient to determine a reliable navigation path, do NOT invent steps. Return an empty path array: {\"destination\": \"Unknown\", \"path\": []}.";

    public static String buildUserPrompt(PathRequest request) {
        StringBuilder sb = new StringBuilder();
        UserIntent intent = request.getIntent();

        sb.append("User Request Intent (Candidate):\n").append(intent.getIntentName()).append("\n\n");
        sb.append("User Query:\n\"").append(intent.getRawQuery()).append("\"\n\n");
        if (!intent.getTargetCategory().isEmpty()) {
            sb.append("Target Category:\n").append(intent.getTargetCategory()).append("\n\n");
        }

        // 1. Extracted Parameters
        Map<String, String> params = intent.getParameters();
        if (params != null && !params.isEmpty()) {
            sb.append("Extracted Parameters:\n");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
        }

        // 2. Screen Position
        sb.append("Current Screen Position:\n").append(request.getCurrentPosition()).append("\n\n");

        // 3. Device Context (including SDK/API level)
        JSONObject devInfo = request.getDeviceInfo();
        if (devInfo != null && devInfo.length() > 0) {
            sb.append("Device Context:\n");
            sb.append("- Manufacturer: ").append(devInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(devInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(devInfo.optString("android_version", "Unknown")).append("\n");
            int sdkInt = devInfo.optInt("sdk_int", 0);
            if (sdkInt > 0) {
                sb.append("- Android SDK/API Level: ").append(sdkInt).append("\n");
            }
            String customOs = devInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS/ROM: ").append(customOs).append(" ").append(devInfo.optString("custom_os_version", "")).append("\n");
            }
            sb.append("\n");
        }

        // 4. Navigation Graph Context
        JSONObject graph = request.getNavGraph();
        if (graph != null && graph.length() > 0) {
            sb.append("Available Nav Graph Context:\n").append(graph.toString()).append("\n\n");
        } else {
            sb.append("Nav Graph Context: Standard Android Settings hierarchy applies.\n\n");
        }

        // 5. Verification and Generation Instruction
        sb.append("Generate the ordered navigation path from current position to the intended target.\n");
        sb.append("Treat the provided intent and parameters as candidate classification results. ");
        sb.append("Verify that the candidate intent, parameters, device information, and navigation path are consistent with the original user query before generating the response.");

        return sb.toString();
    }
}
