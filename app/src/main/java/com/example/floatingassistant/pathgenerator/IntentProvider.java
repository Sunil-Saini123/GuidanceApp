package com.example.floatingassistant.pathgenerator;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * IntentProvider — Provides predefined intents for path generation testing
 * and fallback resolution when the live Intent Classifier module is not yet connected.
 */
public class IntentProvider {

    private static final Map<String, UserIntent> PREDEFINED_INTENTS = new HashMap<>();

    static {
        registerIntent(new UserIntent("ENABLE_BLUETOOTH", "Turn on Bluetooth", "Settings", Map.of("setting", "bluetooth")));
        registerIntent(new UserIntent("OPEN_WIFI_SETTINGS", "Open Wi-Fi settings", "Settings", Map.of("setting", "wifi")));
        registerIntent(new UserIntent("OPEN_DISPLAY_SETTINGS", "Open Display settings", "Settings", Map.of("setting", "display")));
        registerIntent(new UserIntent("OPEN_SECURITY_PRIVACY", "Go to Security and privacy", "Settings", Map.of("setting", "security")));
        registerIntent(new UserIntent("CHANGE_WALLPAPER", "Change wallpaper", "Settings", Map.of("setting", "wallpaper")));
        registerIntent(new UserIntent("OPEN_BATTERY_SAVER", "Turn on battery saver", "Settings", Map.of("setting", "battery")));
        registerIntent(new UserIntent("OPEN_ACCESSIBILITY_SETTINGS", "Open Accessibility settings", "Settings", Map.of("setting", "accessibility")));
        registerIntent(new UserIntent("OPEN_SOUND_SETTINGS", "Adjust sound settings", "Settings", Map.of("setting", "sound")));
    }

    private static void registerIntent(UserIntent intent) {
        PREDEFINED_INTENTS.put(intent.getIntentName().toUpperCase(Locale.US), intent);
    }

    /**
     * Finds a matching predefined UserIntent for a given natural language query or intent string.
     */
    public static UserIntent findMatchingIntent(String queryOrIntent) {
        if (queryOrIntent == null || queryOrIntent.trim().isEmpty()) {
            return new UserIntent("UNKNOWN", "Empty query");
        }

        String normalized = queryOrIntent.trim().toUpperCase(Locale.US);

        // Exact match by intent name
        if (PREDEFINED_INTENTS.containsKey(normalized)) {
            return PREDEFINED_INTENTS.get(normalized);
        }

        // Fuzzy match by query content
        String lowerQuery = queryOrIntent.toLowerCase(Locale.US);
        if (lowerQuery.contains("bluetooth")) {
            return PREDEFINED_INTENTS.get("ENABLE_BLUETOOTH");
        } else if (lowerQuery.contains("wi-fi") || lowerQuery.contains("wifi")) {
            return PREDEFINED_INTENTS.get("OPEN_WIFI_SETTINGS");
        } else if (lowerQuery.contains("display") || lowerQuery.contains("brightness")) {
            return PREDEFINED_INTENTS.get("OPEN_DISPLAY_SETTINGS");
        } else if (lowerQuery.contains("security") || lowerQuery.contains("privacy")) {
            return PREDEFINED_INTENTS.get("OPEN_SECURITY_PRIVACY");
        } else if (lowerQuery.contains("wallpaper")) {
            return PREDEFINED_INTENTS.get("CHANGE_WALLPAPER");
        } else if (lowerQuery.contains("battery")) {
            return PREDEFINED_INTENTS.get("OPEN_BATTERY_SAVER");
        } else if (lowerQuery.contains("accessibility")) {
            return PREDEFINED_INTENTS.get("OPEN_ACCESSIBILITY_SETTINGS");
        } else if (lowerQuery.contains("sound") || lowerQuery.contains("volume")) {
            return PREDEFINED_INTENTS.get("OPEN_SOUND_SETTINGS");
        }

        // Fallback: create a custom intent from the query string
        return new UserIntent("GENERIC_NAVIGATE", queryOrIntent);
    }

    public static Map<String, UserIntent> getAllPredefinedIntents() {
        return new HashMap<>(PREDEFINED_INTENTS);
    }
}
