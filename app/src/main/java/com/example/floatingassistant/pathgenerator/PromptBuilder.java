package com.example.floatingassistant.pathgenerator;

import org.json.JSONArray;
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
            "3. ALWAYS start the path from 'Home' (the device home screen) unless the user is already inside the target app.\n" +
            "4. Include EVERY intermediate step — do NOT skip steps. For example:\n" +
            "   Wrong (too short): [\"Settings\", \"Profile Picture\"]\n" +
            "   Correct (complete): [\"Home\", \"WhatsApp\", \"Menu\", \"Settings\", \"Account\", \"Profile Picture\"]\n" +
            "5. The path array MUST ONLY contain the names of clickable buttons, menus, and tabs. NEVER include instructional actions like 'Scroll down', 'Swipe', 'Click', or 'Wait'. If a user needs to scroll to see 'About phone', just output 'About phone' as the next step.\n" +
            "   Wrong: [\"Settings\", \"Scroll down\", \"About phone\"]\n" +
            "   Correct: [\"Settings\", \"About phone\"]\n" +
            "6. Keep screen and item names accurate for the specific device manufacturer and OEM ROM.\n" +
            "7. Verify that each consecutive navigation step is realistically reachable from the previous step.\n" +
            "8. If insufficient information, return: {\"destination\": \"Unknown\", \"path\": []}";

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
            if (sdkInt > 0) sb.append("- Android SDK/API Level: ").append(sdkInt).append("\n");
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

        sb.append("Generate the ordered navigation path. Treat the provided intent and parameters as candidate classification results. Verify consistency before responding.");
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
     */
    public static String buildGeminiDrivenPrompt(
            String targetApp,
            String exactTask,
            String cleanPageContent,
            JSONObject deviceInfo,
            boolean isOnTargetApp,
            String failedPath) {
        return buildGeminiDrivenPrompt(targetApp, exactTask, cleanPageContent, deviceInfo, isOnTargetApp, failedPath, null, null);
    }

    /**
     * [PERMANENT FIX] Do not remove this! Full-Context Reroute Prompt Generator.
     * Injects exact live screen title, local SQLite graph database knowledge (.db), and generic button
     * disambiguation rules so AI never confuses duplicate controls (e.g. 3-dots on root vs 3-dots in a chat).
     */
    public static String buildGeminiDrivenPrompt(
            String targetApp,
            String exactTask,
            String cleanPageContent,
            JSONObject deviceInfo,
            boolean isOnTargetApp,
            String failedPath,
            String currentScreenTitle,
            JSONObject navGraphContext) {

        StringBuilder sb = new StringBuilder();

        sb.append("Target App: ").append(targetApp).append("\n");
        sb.append("Task to accomplish: ").append(exactTask).append("\n");
        if (currentScreenTitle != null && !currentScreenTitle.trim().isEmpty()) {
            sb.append("Current Screen Title: \"").append(currentScreenTitle).append("\"\n");
        }
        sb.append("\n");

        if (deviceInfo != null && deviceInfo.length() > 0) {
            sb.append("Device Context:\n");
            sb.append("- Manufacturer: ").append(deviceInfo.optString("manufacturer", "Unknown")).append("\n");
            sb.append("- Model: ").append(deviceInfo.optString("model", "Unknown")).append("\n");
            sb.append("- Android Version: ").append(deviceInfo.optString("android_version", "Unknown")).append("\n");
            int sdkInt = deviceInfo.optInt("sdk_int", 0);
            if (sdkInt > 0) sb.append("- Android SDK/API Level: ").append(sdkInt).append("\n");
            String customOs = deviceInfo.optString("custom_os", "stock");
            if (!"stock".equalsIgnoreCase(customOs)) {
                sb.append("- Custom OS: ").append(customOs)
                        .append(" ").append(deviceInfo.optString("custom_os_version", "")).append("\n");
            }
            sb.append("\n");
        }

        if (navGraphContext != null && navGraphContext.length() > 0) {
            sb.append("Verified Local App Navigation Graph (.db):\n").append(navGraphContext.toString()).append("\n\n");
        }

        // [PERMANENT FIX] Do not remove this! Format screen elements concisely so the AI sees all elements on screen without truncation.
        sb.append(formatScreenElements(cleanPageContent));

        // Path starting point instruction based on current context
        if (isOnTargetApp) {
            sb.append("IMPORTANT: The user is ALREADY INSIDE the target app (").append(targetApp).append("). ");
            sb.append("Start the path from the current screen — do NOT include 'Home' or the app launch step. ");
            sb.append("Include EVERY intermediate screen, menu, or tap needed. Do NOT skip steps.\n");
            sb.append("Example for WhatsApp profile picture: [\"Menu\", \"Settings\", \"Account\", \"Profile Picture\"]\n\n");
        } else {
            sb.append("IMPORTANT: The user is on a DIFFERENT app or the home screen. ");
            sb.append("Start the navigation path from the device HOME SCREEN, then open the app. ");
            sb.append("Include EVERY intermediate screen, menu, or tap needed. Do NOT skip steps.\n");
            sb.append("Example for WhatsApp profile picture: [\"Home\", \"WhatsApp\", \"Menu\", \"Settings\", \"Account\", \"Profile Picture\"]\n\n");
        }

        sb.append("CRITICAL RULES:\n");
        if (failedPath != null && !failedPath.trim().isEmpty()) {
            sb.append("0. [PERMANENT FIX] The user previously tried this path but it FAILED because the menus did not exist: [").append(failedPath).append("]. DO NOT suggest this path again! You MUST find an alternative route using the visible Screen Elements.\n");
        }
        sb.append("1. The path array must be COMPLETE with ALL steps — short paths with missing intermediate steps are WRONG.\n");
        sb.append("2. DO NOT output action verbs like 'Scroll down', 'Swipe', 'Click', or 'Wait' — only the exact names of buttons/elements to tap.\n");
        sb.append("3. If the 'Current Screen Elements' section above lists visible UI elements, you MUST use those EXACT element names in your path. Do NOT invent different names for elements that are already visible.\n");
        sb.append("4. If the target element is NOT visible in the screen snapshot (user needs to scroll), use the CORRECT name for THIS specific device and OEM ROM. ");
        sb.append("Different OEMs use different names! For example:\n");
        sb.append("   - AOSP/Pixel: 'About phone'  |  ColorOS/Realme/OPPO: 'About device'  |  Samsung OneUI: 'About phone'  |  MIUI/Xiaomi: 'About phone'\n");
        sb.append("   - AOSP: 'Display'  |  ColorOS: 'Display & brightness'\n");
        sb.append("   Check the Device Context above to determine the correct OEM naming.\n");
        sb.append("5. Disambiguate Generic & Ambiguous Controls: Generic buttons like 'More options' (3 dots), 'Search', 'Menu', or 'Settings' exist on multiple different screens with totally different functions. You MUST make sure each step realistically belongs to the CURRENT screen. If the user is on the wrong screen (e.g. inside a wrong chat or wrong sub-page), output 'Back' first to return to the parent screen before proceeding.\n");
        sb.append("6. Generate the exact ordered navigation path to accomplish the task.\n");
        return sb.toString();
    }

    // [PERMANENT FIX] Do not remove this! Format screen elements concisely so the AI can see the ENTIRE screen without hitting character limits.
    private static String formatScreenElements(String rawContent) {
        if (rawContent == null || rawContent.trim().isEmpty()) {
            return "Current Screen Elements: Not available - use standard app navigation.\n\n";
        }
        try {
            JSONObject obj = new JSONObject(rawContent);
            JSONArray elements = obj.optJSONArray("elements");
            if (elements != null && elements.length() > 0) {
                StringBuilder list = new StringBuilder("Current Screen Elements (live UI snapshot):\n");
                for (int i = 0; i < elements.length(); i++) {
                    JSONObject el = elements.optJSONObject(i);
                    if (el == null) continue;
                    String name = el.optString("name", "").trim();
                    if (!name.isEmpty()) {
                        boolean clickable = el.optBoolean("is_clickable", false);
                        list.append("- \"").append(name).append("\"");
                        if (clickable) list.append(" [clickable]");
                        list.append("\n");
                    }
                }
                list.append("\n");
                return list.toString();
            }
        } catch (Exception ignored) {
            // If it's not standard JSON, fall back to string handling with generous limit
        }

        String screen = rawContent.trim();
        if (screen.length() > GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) {
            screen = screen.substring(0, GroqProxyClient.MAX_SCREEN_CONTENT_CHARS) + "\n... [truncated]";
        }
        return "Current Screen Elements (live UI snapshot):\n" + screen + "\n\n";
    }
}
