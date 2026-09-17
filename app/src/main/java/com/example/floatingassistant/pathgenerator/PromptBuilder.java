package com.example.floatingassistant.pathgenerator;

import org.json.JSONObject;

import java.util.Map;

/**
 * PromptBuilder - Builds structured LLM prompts for Android UI navigation path planning.
 */
public class PromptBuilder {

    public static final String SYSTEM_PROMPT =
            "You are an Android Navigation Assistant expert. Your task is to produce the COMPLETE, EXACT sequence of every UI screen and menu item required for a user to accomplish a task on their Android device.\n\n" +
            "CRITICAL INSTRUCTIONS:\n" +
            "1. Output MUST be strict JSON only. Do NOT include markdown code blocks (```json) or extra text.\n" +
            "2. Required JSON format:\n" +
            "{\n" +
            "  \"destination\": \"<Final Target Screen or Option Name>\",\n" +
            "  \"path\": [\"<Step 1>\", \"<Step 2>\", \"<Step 3>\", \"<Step 4>\", \"<Step 5>\", \"...all steps...\"]\n" +
            "}\n" +
            "3. If the user is on the home screen, start with the target app name (e.g. \"Settings\"). If already in the app, start from the current screen.\n" +
            "4. Include EVERY intermediate step — do NOT skip steps. A path MUST navigate all the way to the specific destination screen or setting inside the app. A single-step path consisting only of the target app name (e.g. [\"Settings\"]) is STRICTLY FORBIDDEN and will be rejected.\n" +
            "   Wrong (trivial 1-step): [\"Settings\"]\n" +
            "   Wrong (missing steps): [\"Settings\", \"Font size\"]\n" +
            "   Correct (complete): [\"Settings\", \"Display, brightness & eye protection\", \"Font size and style\"]\n" +
            "5. Keep screen and item names accurate for the specific device manufacturer and OEM ROM.\n" +
            "6. Verify that each consecutive navigation step is realistically reachable from the previous step.\n" +
            "7. If insufficient information, return: {\"destination\": \"Unknown\", \"path\": []}";


    public static String buildUserPrompt(PathRequest request) {
        StringBuilder sb = new StringBuilder();
        UserIntent intent = request.getIntent();

        sb.append("User Request Intent:\n").append(intent.getIntentName()).append("\n\n");
        sb.append("User Query:\n\"").append(intent.getRawQuery()).append("\"\n\n");
        if (!intent.getTargetCategory().isEmpty()) {
            sb.append("Target Category:\n").append(intent.getTargetCategory()).append("\n\n");
        }

        Map<String, String> params = intent.getParameters();
        if (params != null && !params.isEmpty()) {
            sb.append("Extracted Parameters:\n");
            for (Map.Entry<String, String> entry : params.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
            sb.append("\n");
        }

        sb.append("Current Screen Position:\n").append(request.getCurrentPosition()).append("\n\n");

        JSONObject devInfo = request.getDeviceInfo();
        if (devInfo != null && devInfo.length() > 0) {
            sb.append("Device Context:\n");
            sb.append("- Manufacturer: ").append(devInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(devInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(devInfo.optString("android_version", "Unknown")).append("\n");
            int sdkInt = devInfo.optInt("sdk_int", 0);
            if (sdkInt > 0) sb.append("- SDK: ").append(sdkInt).append("\n");
            String customOs = devInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS: ").append(customOs)
                        .append(" ").append(devInfo.optString("custom_os_version", "")).append("\n");
            }
            sb.append("\n");
        }

        JSONObject graph = request.getNavGraph();
        if (graph != null && graph.length() > 0) {
            sb.append("Nav Graph Context:\n").append(graph.toString()).append("\n\n");
        }

        sb.append("Generate the ordered navigation path. Verify consistency before responding.");
        return sb.toString();
    }

    /**
     * Builds a focused user prompt for Tier 3 Groq fallback using Gemini-parsed intent
     * (targetApp + exactTask), live screen state from clean_page.json, and whether the
     * user is already on the target app.
     *
     * isOnTargetApp controls the starting point of the path:
     *   true  → user is already inside the target app → start from within the app
     *           e.g.  WhatsApp → 3 dots → Settings → ...
     *   false → user is on a different app / home screen → start from Home
     *           e.g.  Home → WhatsApp → 3 dots → Settings → ...
     *
     * cleanPageContent is truncated to GroqProxyClient.MAX_SCREEN_CONTENT_CHARS to avoid
     * HTTP 413 (Request Entity Too Large) from the Vercel proxy.
     */
    public static String buildGeminiDrivenPrompt(
            String targetApp,
            String exactTask,
            String cleanPageContent,
            JSONObject deviceInfo,
            boolean isOnTargetApp) {

        StringBuilder sb = new StringBuilder();

        sb.append("Target App: ").append(targetApp).append("\n");
        sb.append("Task to accomplish: ").append(exactTask).append("\n\n");

        if (deviceInfo != null && deviceInfo.length() > 0) {
            sb.append("Device Context:\n");
            sb.append("- Manufacturer: ").append(deviceInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(deviceInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(deviceInfo.optString("android_version", "Unknown")).append("\n");
            int sdkInt = deviceInfo.optInt("sdk_int", 0);
            if (sdkInt > 0) sb.append("- SDK: ").append(sdkInt).append("\n");
            String customOs = deviceInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS: ").append(customOs)
                        .append(" ").append(deviceInfo.optString("custom_os_version", "")).append("\n");
            }
            sb.append("\n");
        }

        // Truncate screen content to avoid HTTP 413 from Vercel proxy
        if (cleanPageContent != null && !cleanPageContent.trim().isEmpty()) {
            String screen = cleanPageContent.trim();
            if (screen.length() > GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) {
                screen = screen.substring(0, GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) + "\n... [truncated]";
            }
            sb.append("Current Screen Elements (live UI snapshot):\n");
            sb.append(screen).append("\n\n");
        } else {
            sb.append("Current Screen Elements: Not available - use standard app navigation.\n\n");
        }

        // Path starting point instruction based on current context
        if (isOnTargetApp) {
            sb.append("IMPORTANT: The user is ALREADY INSIDE the target app (").append(targetApp).append("). ");
            sb.append("Start the path from inside the app — do NOT include 'Home' or the app launch step. ");
            sb.append("Include EVERY intermediate screen, menu, or tap needed. Do NOT skip steps.\n");
            sb.append("Example for Settings font size: [\"Display, brightness & eye protection\", \"Font size and style\"]\n\n");
        } else {
            sb.append("IMPORTANT: The user is on a DIFFERENT app or the home screen. ");
            sb.append("Start the navigation path with the target app name (e.g. \"").append(targetApp).append("\"), then navigate INSIDE the app. ");
            sb.append("Include EVERY intermediate screen, menu, or tap needed to reach the destination setting/task.\n");
            sb.append("Example for Settings font size: [\"Settings\", \"Display, brightness & eye protection\", \"Font size and style\"]\n\n");
        }

        sb.append("CRITICAL: The path array must be COMPLETE with ALL intermediate steps leading to the task. ");
        sb.append("A path of only 1 step (e.g. [\"").append(targetApp).append("\"]) is INVALID and FORBIDDEN because the destination is INSIDE the app. ");
        sb.append("Generate the exact ordered multi-step navigation path to accomplish the task. ");
        sb.append("Use real UI element names for this device ROM.");
        return sb.toString();

    }

    /**
     * Recovery prompt: used when the user says they did NOT reach the destination.
     * Tells Groq the current screen/package and asks for a corrected path FROM
     * that position to the intended destination.
     */
    public static String buildRecoveryPrompt(
            String targetApp,
            String exactTask,
            String currentScreen,
            String currentPkg,
            String cleanPageContent,
            org.json.JSONObject deviceInfo,
            boolean isOnTargetApp) {

        StringBuilder sb = new StringBuilder();
        sb.append("Target App: ").append(targetApp).append("\n");
        sb.append("Task to accomplish: ").append(exactTask).append("\n\n");
        sb.append("WHERE THE USER IS RIGHT NOW:\n");
        sb.append("- Current screen name: ").append(currentScreen.isEmpty() ? "Unknown" : currentScreen).append("\n");
        sb.append("- Current app package: ").append(currentPkg.isEmpty() ? "Unknown" : currentPkg).append("\n");
        sb.append("- Is already inside target app: ").append(isOnTargetApp).append("\n\n");
        sb.append("The user attempted to reach the destination but could not find it. ");
        sb.append("They are currently on the screen described above.\n\n");

        if (deviceInfo != null && deviceInfo.length() > 0) {
            sb.append("Device Context:\n");
            sb.append("- Manufacturer: ").append(deviceInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(deviceInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(deviceInfo.optString("android_version", "Unknown")).append("\n");
            String customOs = deviceInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS: ").append(customOs)
                        .append(" ").append(deviceInfo.optString("custom_os_version", "")).append("\n");
            }
            sb.append("\n");
        }

        if (cleanPageContent != null && !cleanPageContent.trim().isEmpty()) {
            String screen = cleanPageContent.trim();
            if (screen.length() > GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) {
                screen = screen.substring(0, GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) + "\n... [truncated]";
            }
            sb.append("Current Screen Elements (live UI snapshot):\n").append(screen).append("\n\n");
        }

        sb.append("Generate a CORRECTED, COMPLETE navigation path starting from the user's CURRENT SCREEN to reach the destination.\n");
        sb.append("Include EVERY step. Do NOT skip any intermediate screen or tap.\n");
        sb.append("If already on the target app, start from inside the app (do not include Home).\n");
        sb.append("Use real UI element names visible on this specific device ROM.");
        return sb.toString();
    }
}