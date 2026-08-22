package com.example.floatingassistant.pathgenerator;

import android.content.Context;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * PathGenerator — Core entry point for generating navigation paths.
 * Orchestrates intent processing, device metadata gathering, Groq proxy communication,
 * path validation, and fallback mechanisms.
 */
public class PathGenerator {

    private static final String TAG = "PathGenerator";

    private final GroqProxyClient groqClient;

    public PathGenerator(GroqProxyClient groqClient) {
        this.groqClient = groqClient != null ? groqClient : new GroqProxyClient();
    }

    public PathGenerator() {
        this(new GroqProxyClient());
    }

    public GroqProxyClient getGroqClient() {
        return groqClient;
    }

    /**
     * Resolves a navigation path for a given PathRequest synchronously.
     * Should be called from a background IO thread.
     */
    public NavigationPath generatePath(Context context, PathRequest request) {
        if (request == null || !request.getIntent().isValid()) {
            return NavigationPath.failure("Invalid or missing intent request");
        }

        AppLogger.i(TAG, "Generating path for Intent: " + request.getIntent().getIntentName() +
                ", Query: \"" + request.getIntent().getRawQuery() + "\", Pos: " + request.getCurrentPosition());

        // 1. Gather device info if missing in request
        JSONObject deviceInfo = request.getDeviceInfo();
        if (deviceInfo.length() == 0 && context != null) {
            deviceInfo = DeviceInfoGatherer.gather(context);
        }

        // 2. Build full request with gathered device info
        PathRequest fullRequest = new PathRequest(
                request.getIntent(),
                request.getCurrentPosition(),
                deviceInfo,
                request.getNavGraph()
        );

        // 3. Attempt path resolution via Groq Proxy
        try {
            String rawOutput = groqClient.sendRequest(fullRequest);
            NavigationPath validated = PathValidator.validate(rawOutput);

            if (validated.isValid()) {
                return validated;
            }
            AppLogger.w(TAG, "Groq proxy returned invalid path: " + validated.getErrorMessage());
        } catch (Exception e) {
            AppLogger.e(TAG, "Groq proxy communication failed: " + e.getMessage(), e);
        }

        // 4. Fallback resolution for predefined intents when proxy is unavailable or invalid
        AppLogger.i(TAG, "Falling back to predefined path rules for intent: " + request.getIntent().getIntentName());
        return resolveFallbackPath(fullRequest);
    }

    /**
     * Convenience overload accepting intent and current position.
     */
    public NavigationPath generatePath(Context context, UserIntent intent, String currentPosition) {
        return generatePath(context, new PathRequest(intent, currentPosition));
    }

    /**
     * Convenience overload accepting a natural language query.
     */
    public NavigationPath generatePathForQuery(Context context, String query, String currentPosition) {
        UserIntent intent = IntentProvider.findMatchingIntent(query);
        return generatePath(context, intent, currentPosition);
    }

    /**
     * Generates a deterministic fallback path for predefined intents when LLM/proxy is unreachable.
     */
    private NavigationPath resolveFallbackPath(PathRequest request) {
        String intentName = request.getIntent().getIntentName();
        String pos = request.getCurrentPosition();

        List<String> steps;
        String destination;

        switch (intentName.toUpperCase()) {
            case "ENABLE_BLUETOOTH":
                destination = "Bluetooth";
                steps = Arrays.asList(pos, "Connected devices", "Bluetooth");
                break;
            case "OPEN_WIFI_SETTINGS":
                destination = "Wi-Fi";
                steps = Arrays.asList(pos, "Network & internet", "Wi-Fi");
                break;
            case "OPEN_DISPLAY_SETTINGS":
                destination = "Display";
                steps = Arrays.asList(pos, "Display");
                break;
            case "OPEN_SECURITY_PRIVACY":
                destination = "Security & privacy";
                steps = Arrays.asList(pos, "Security & privacy");
                break;
            case "CHANGE_WALLPAPER":
                destination = "Wallpaper & style";
                steps = Arrays.asList(pos, "Wallpaper & style");
                break;
            case "OPEN_BATTERY_SAVER":
                destination = "Battery saver";
                steps = Arrays.asList(pos, "Battery", "Battery saver");
                break;
            case "OPEN_ACCESSIBILITY_SETTINGS":
                destination = "Accessibility";
                steps = Arrays.asList(pos, "Accessibility");
                break;
            case "OPEN_SOUND_SETTINGS":
                destination = "Sound & vibration";
                steps = Arrays.asList(pos, "Sound & vibration");
                break;
            default:
                destination = request.getIntent().getRawQuery();
                steps = Arrays.asList(pos, destination);
                break;
        }

        return NavigationPath.success(destination, steps, "Fallback rule applied");
    }
}
