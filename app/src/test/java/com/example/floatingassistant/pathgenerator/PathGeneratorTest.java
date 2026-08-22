package com.example.floatingassistant.pathgenerator;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * PathGeneratorTest — Unit tests for Java Path Generation module,
 * covering prompt generation, response validation, intent matching, and mock proxy integration.
 */
public class PathGeneratorTest {

    private UserIntent mockIntent;
    private PathRequest mockRequest;

    @Before
    public void setUp() {
        mockIntent = new UserIntent("ENABLE_BLUETOOTH", "Turn on Bluetooth", "Settings", null);
        JSONObject deviceInfo = new JSONObject();
        try {
            deviceInfo.put("manufacturer", "Google");
            deviceInfo.put("model", "Pixel 7");
            deviceInfo.put("android_version", "14");
            deviceInfo.put("custom_os", "stock");
        } catch (Exception ignored) {}

        mockRequest = new PathRequest(mockIntent, "SettingsHomepage", deviceInfo, null);
    }

    @Test
    public void testPromptBuilderOutput() {
        String prompt = PromptBuilder.buildUserPrompt(mockRequest);
        assertNotNull(prompt);
        assertTrue(prompt.contains("ENABLE_BLUETOOTH"));
        assertTrue(prompt.contains("Turn on Bluetooth"));
        assertTrue(prompt.contains("Google"));
        assertTrue(prompt.contains("Pixel 7"));
        assertTrue(prompt.contains("SettingsHomepage"));
    }

    @Test
    public void testPathValidatorValidJson() {
        String json = "{\n" +
                "  \"destination\": \"Bluetooth\",\n" +
                "  \"path\": [\"SettingsHomepage\", \"Connected devices\", \"Bluetooth\"]\n" +
                "}";

        NavigationPath path = PathValidator.validate(json);
        assertTrue(path.isValid());
        assertEquals("Bluetooth", path.getDestination());
        assertEquals(3, path.getSteps().size());
        assertEquals("SettingsHomepage -> Connected devices -> Bluetooth", path.toPathString());
    }

    @Test
    public void testPathValidatorJsonWithMarkdownFences() {
        String jsonWithFences = "```json\n" +
                "{\n" +
                "  \"destination\": \"Wi-Fi\",\n" +
                "  \"path\": [\"SettingsHomepage\", \"Network & internet\", \"Wi-Fi\"]\n" +
                "}\n" +
                "```";

        NavigationPath path = PathValidator.validate(jsonWithFences);
        assertTrue(path.isValid());
        assertEquals("Wi-Fi", path.getDestination());
        assertEquals("SettingsHomepage -> Network & internet -> Wi-Fi", path.toPathString());
    }

    @Test
    public void testPathValidatorInvalidJson() {
        String invalidJson = "This is not json output";
        NavigationPath path = PathValidator.validate(invalidJson);
        assertFalse(path.isValid());
        assertNotNull(path.getErrorMessage());
    }

    @Test
    public void testPathValidatorEmptyPathArray() {
        String emptyPathJson = "{\"destination\": \"Bluetooth\", \"path\": []}";
        NavigationPath path = PathValidator.validate(emptyPathJson);
        assertFalse(path.isValid());
    }

    @Test
    public void testIntentProviderMatching() {
        UserIntent intent1 = IntentProvider.findMatchingIntent("Turn on Bluetooth");
        assertEquals("ENABLE_BLUETOOTH", intent1.getIntentName());

        UserIntent intent2 = IntentProvider.findMatchingIntent("wifi settings");
        assertEquals("OPEN_WIFI_SETTINGS", intent2.getIntentName());

        UserIntent intent3 = IntentProvider.findMatchingIntent("custom unmapped query");
        assertEquals("GENERIC_NAVIGATE", intent3.getIntentName());
    }

    @Test
    public void testPathGeneratorFallbackResolution() {
        PathGenerator generator = new PathGenerator(new GroqProxyClient("http://invalid-host:9999/v1", 100, "model"));
        NavigationPath path = generator.generatePath(null, mockRequest);

        assertTrue(path.isValid());
        assertEquals("Bluetooth", path.getDestination());
        assertEquals("SettingsHomepage -> Connected devices -> Bluetooth", path.toPathString());
    }

    @Test
    public void testPathGeneratorMockProxySuccess() {
        GroqProxyClient mockProxy = new GroqProxyClient() {
            @Override
            public String sendRequest(PathRequest request) throws Exception {
                return "{\n" +
                        "  \"destination\": \"Display\",\n" +
                        "  \"path\": [\"SettingsHomepage\", \"Display\"]\n" +
                        "}";
            }
        };

        PathGenerator generator = new PathGenerator(mockProxy);
        UserIntent displayIntent = new UserIntent("OPEN_DISPLAY_SETTINGS", "Open Display settings");
        NavigationPath path = generator.generatePath(null, new PathRequest(displayIntent, "SettingsHomepage"));

        assertTrue(path.isValid());
        assertEquals("Display", path.getDestination());
        assertEquals("SettingsHomepage -> Display", path.toPathString());
    }
}
