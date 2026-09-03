package com.example.floatingassistant.intent.evaluation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * IntentEvaluationDataset — Standard labeled test dataset for benchmarking
 * intent classification accuracy, precision, recall, and rejection safety.
 */
public class IntentEvaluationDataset {

    public static class TestCase {
        private final String query;
        private final String expectedIntent;
        private final boolean expectReject;

        public TestCase(String query, String expectedIntent, boolean expectReject) {
            this.query = query;
            this.expectedIntent = expectedIntent;
            this.expectReject = expectReject;
        }

        public TestCase(String query, String expectedIntent) {
            this(query, expectedIntent, false);
        }

        public String getQuery() { return query; }
        public String getExpectedIntent() { return expectedIntent; }
        public boolean isExpectReject() { return expectReject; }
    }

    private static final List<TestCase> BENCHMARK_CASES = new ArrayList<>();

    static {
        // Wi-Fi Domain
        add("Connect to Rohit's wireless network", "CONNECT_WIFI");
        add("Join Rohit Wi-Fi", "CONNECT_WIFI");
        add("Connect to office wifi network", "CONNECT_WIFI");
        add("Turn on Wi-Fi", "ENABLE_WIFI");
        add("Enable wifi adapter", "ENABLE_WIFI");
        add("Turn off Wi-Fi", "DISABLE_WIFI");
        add("Disable wifi", "DISABLE_WIFI");
        add("Open Wi-Fi settings", "OPEN_WIFI_SETTINGS");
        add("Show me available wifi networks", "OPEN_WIFI_SETTINGS");
        add("Forget my saved Wi-Fi network", "FORGET_WIFI");

        // Bluetooth Domain
        add("Turn on Bluetooth", "ENABLE_BLUETOOTH");
        add("Enable bluetooth connection", "ENABLE_BLUETOOTH");
        add("Turn off Bluetooth", "DISABLE_BLUETOOTH");
        add("Disable bluetooth", "DISABLE_BLUETOOTH");

        // Display Domain
        add("Open Display settings", "OPEN_DISPLAY_SETTINGS");
        add("Increase screen brightness", "OPEN_DISPLAY_SETTINGS");
        add("Set brightness to 50%", "OPEN_DISPLAY_SETTINGS");
        add("Adjust display brightness", "OPEN_DISPLAY_SETTINGS");

        // Security Domain
        add("Go to Security and privacy", "OPEN_SECURITY_PRIVACY");
        add("Open security settings", "OPEN_SECURITY_PRIVACY");
        add("Show privacy permissions", "OPEN_SECURITY_PRIVACY");

        // Wallpaper / Customization
        add("Change wallpaper", "CHANGE_WALLPAPER");
        add("Set a new wallpaper style", "CHANGE_WALLPAPER");

        // Battery Domain
        add("Turn on battery saver", "OPEN_BATTERY_SAVER");
        add("Open battery settings", "OPEN_BATTERY_SAVER");

        // Sound Domain
        add("Adjust sound settings", "OPEN_SOUND_SETTINGS");
        add("Change ringtone volume", "OPEN_SOUND_SETTINGS");

        // Accessibility Domain
        add("Open Accessibility settings", "OPEN_ACCESSIBILITY_SETTINGS");
        add("Show accessibility screen reader", "OPEN_ACCESSIBILITY_SETTINGS");

        // Ambiguous / Out-of-domain (Must be safely rejected or routed to Groq)
        addReject("Order pizza from dominos");
        addReject("Play some rock music on spotify");
        addReject("What is the weather today");
    }

    private static void add(String query, String expectedIntent) {
        BENCHMARK_CASES.add(new TestCase(query, expectedIntent, false));
    }

    private static void addReject(String query) {
        BENCHMARK_CASES.add(new TestCase(query, "UNKNOWN", true));
    }

    public static List<TestCase> getBenchmarkCases() {
        return Collections.unmodifiableList(BENCHMARK_CASES);
    }
}
