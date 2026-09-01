package com.example.floatingassistant.intent.catalog;

import com.example.floatingassistant.intent.model.IntentDefinition;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * IntentCatalog — Generic registry storing all IntentDefinition objects.
 * The matching and ranking engine dynamically consumes this catalog without hardcoding intent rules.
 */
public class IntentCatalog {

    private final Map<String, IntentDefinition> intents = new LinkedHashMap<>();

    public IntentCatalog() {
        seedDefaultIntents();
    }

    public static IntentCatalog defaultCatalog() {
        return new IntentCatalog();
    }

    public synchronized void registerIntent(IntentDefinition definition) {
        if (definition != null && !definition.getIntentName().isEmpty()) {
            intents.put(definition.getIntentName().toUpperCase(Locale.US), definition);
        }
    }

    public synchronized IntentDefinition findById(String intentName) {
        if (intentName == null) return null;
        return intents.get(intentName.trim().toUpperCase(Locale.US));
    }

    public synchronized List<IntentDefinition> getAllIntents() {
        return new ArrayList<>(intents.values());
    }

    public synchronized List<IntentDefinition> getByCategory(String category) {
        if (category == null) return Collections.emptyList();
        List<IntentDefinition> result = new ArrayList<>();
        for (IntentDefinition def : intents.values()) {
            if (category.equalsIgnoreCase(def.getCategory())) {
                result.add(def);
            }
        }
        return result;
    }

    public synchronized int size() {
        return intents.size();
    }

    public synchronized void clear() {
        intents.clear();
    }

    public synchronized void resetToDefaults() {
        intents.clear();
        seedDefaultIntents();
    }

    private void seedDefaultIntents() {
        // 1. CONNECT_WIFI
        Map<String, IntentDefinition.ParameterSpec> connectWifiParams = new HashMap<>();
        connectWifiParams.put("network_name", new IntentDefinition.ParameterSpec(
                IntentDefinition.ParameterType.STRING, true, "Name of the target Wi-Fi network"
        ));
        registerIntent(new IntentDefinition(
                "CONNECT_WIFI",
                "Network",
                "Connect the device to a specified Wi-Fi wireless network",
                Arrays.asList("connect", "join", "link"),
                Arrays.asList("wifi", "wi-fi", "wireless network", "wlan", "hotspot"),
                Arrays.asList("Connect to my Wi-Fi", "Join my wireless network", "Connect to office wifi", "Join wifi network"),
                connectWifiParams
        ));

        // 2. ENABLE_WIFI
        registerIntent(new IntentDefinition(
                "ENABLE_WIFI",
                "Network",
                "Turn on or enable the Wi-Fi adapter",
                Arrays.asList("enable", "turn on", "activate", "switch on", "start"),
                Arrays.asList("wifi", "wi-fi", "wireless", "wlan"),
                Arrays.asList("Turn on Wi-Fi", "Enable wifi", "Turn wifi on", "Switch on wifi"),
                Collections.emptyMap()
        ));

        // 3. DISABLE_WIFI
        registerIntent(new IntentDefinition(
                "DISABLE_WIFI",
                "Network",
                "Turn off or disable the Wi-Fi adapter",
                Arrays.asList("disable", "turn off", "deactivate", "switch off", "stop"),
                Arrays.asList("wifi", "wi-fi", "wireless", "wlan"),
                Arrays.asList("Turn off Wi-Fi", "Disable wifi", "Turn wifi off", "Switch off wifi"),
                Collections.emptyMap()
        ));

        // 4. OPEN_WIFI_SETTINGS
        registerIntent(new IntentDefinition(
                "OPEN_WIFI_SETTINGS",
                "Network",
                "Open the Wi-Fi network configuration settings screen",
                Arrays.asList("open", "go to", "show", "view", "navigate", "see"),
                Arrays.asList("wifi", "wi-fi", "wifi settings", "wireless settings", "wlan settings"),
                Arrays.asList("Open Wi-Fi settings", "Go to wifi", "Show wifi settings", "View wireless networks"),
                Collections.emptyMap()
        ));

        // 5. FORGET_WIFI
        Map<String, IntentDefinition.ParameterSpec> forgetWifiParams = new HashMap<>();
        forgetWifiParams.put("network_name", new IntentDefinition.ParameterSpec(
                IntentDefinition.ParameterType.STRING, false, "Name of the network to forget"
        ));
        registerIntent(new IntentDefinition(
                "FORGET_WIFI",
                "Network",
                "Forget or remove a saved Wi-Fi network connection",
                Arrays.asList("forget", "remove", "delete", "disconnect"),
                Arrays.asList("wifi", "network", "saved network"),
                Arrays.asList("Forget Wi-Fi network", "Remove wifi connection", "Delete saved wifi"),
                forgetWifiParams
        ));

        // 6. ENABLE_BLUETOOTH
        registerIntent(new IntentDefinition(
                "ENABLE_BLUETOOTH",
                "Connected Devices",
                "Turn on or enable Bluetooth connectivity",
                Arrays.asList("enable", "turn on", "activate", "switch on"),
                Arrays.asList("bluetooth", "bt"),
                Arrays.asList("Turn on Bluetooth", "Enable bluetooth", "Turn bluetooth on"),
                Collections.emptyMap()
        ));

        // 7. DISABLE_BLUETOOTH
        registerIntent(new IntentDefinition(
                "DISABLE_BLUETOOTH",
                "Connected Devices",
                "Turn off or disable Bluetooth connectivity",
                Arrays.asList("disable", "turn off", "deactivate", "switch off"),
                Arrays.asList("bluetooth", "bt"),
                Arrays.asList("Turn off Bluetooth", "Disable bluetooth", "Turn bluetooth off"),
                Collections.emptyMap()
        ));

        // 8. OPEN_DISPLAY_SETTINGS
        Map<String, IntentDefinition.ParameterSpec> displayParams = new HashMap<>();
        displayParams.put("value", new IntentDefinition.ParameterSpec(
                IntentDefinition.ParameterType.NUMBER, false, "Brightness percentage value"
        ));
        registerIntent(new IntentDefinition(
                "OPEN_DISPLAY_SETTINGS",
                "Display",
                "Open display and screen brightness settings or adjust brightness level",
                Arrays.asList("open", "go to", "show", "change", "set", "adjust", "increase", "decrease"),
                Arrays.asList("display", "screen", "brightness", "display settings", "screen brightness"),
                Arrays.asList("Open Display settings", "Change brightness", "Increase brightness", "Set brightness to 50%"),
                displayParams
        ));

        // 9. OPEN_SECURITY_PRIVACY
        registerIntent(new IntentDefinition(
                "OPEN_SECURITY_PRIVACY",
                "Security",
                "Open security, privacy, lock screen, and permissions settings",
                Arrays.asList("open", "go to", "show", "view"),
                Arrays.asList("security", "privacy", "security settings", "privacy settings", "permissions"),
                Arrays.asList("Go to Security and privacy", "Open security settings", "Show privacy settings"),
                Collections.emptyMap()
        ));

        // 10. CHANGE_WALLPAPER
        registerIntent(new IntentDefinition(
                "CHANGE_WALLPAPER",
                "Customization",
                "Open wallpaper and home screen styling settings",
                Arrays.asList("change", "set", "update", "customize"),
                Arrays.asList("wallpaper", "background", "wallpaper style", "theme"),
                Arrays.asList("Change wallpaper", "Set wallpaper", "Update background wallpaper"),
                Collections.emptyMap()
        ));

        // 11. OPEN_BATTERY_SAVER
        registerIntent(new IntentDefinition(
                "OPEN_BATTERY_SAVER",
                "Battery",
                "Open battery usage settings or enable battery saver mode",
                Arrays.asList("enable", "turn on", "open", "activate"),
                Arrays.asList("battery", "battery saver", "power saving", "battery percentage"),
                Arrays.asList("Turn on battery saver", "Open battery settings", "Enable power saving mode"),
                Collections.emptyMap()
        ));

        // 12. OPEN_SOUND_SETTINGS
        registerIntent(new IntentDefinition(
                "OPEN_SOUND_SETTINGS",
                "Sound",
                "Open sound, vibration, volume, and ringtone settings",
                Arrays.asList("open", "go to", "adjust", "change", "set"),
                Arrays.asList("sound", "volume", "vibration", "sound settings", "ringtone"),
                Arrays.asList("Adjust sound settings", "Open volume settings", "Change ringtone sound"),
                Collections.emptyMap()
        ));

        // 13. OPEN_ACCESSIBILITY_SETTINGS
        registerIntent(new IntentDefinition(
                "OPEN_ACCESSIBILITY_SETTINGS",
                "Accessibility",
                "Open accessibility services and screen reader settings",
                Arrays.asList("open", "go to", "show", "view"),
                Arrays.asList("accessibility", "accessibility settings", "screen reader"),
                Arrays.asList("Open Accessibility settings", "Go to accessibility", "Show accessibility options"),
                Collections.emptyMap()
        ));
    }
}
