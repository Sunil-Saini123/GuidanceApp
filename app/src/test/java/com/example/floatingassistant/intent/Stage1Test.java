package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.model.ExtractedParameter;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.pathgenerator.UserIntent;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage1Test — Verifies Stage 1 Data Models and Query Preprocessing normalization.
 */
public class Stage1Test {

    @Test
    public void testPreprocessorPreservesRawQuery() {
        String messyQuery = "  Can you connect me to Rohit's WI-FI network!!! ";
        QueryFeatures features = QueryPreprocessor.preprocess(messyQuery);

        assertEquals(messyQuery, features.getRawQuery());
        assertEquals("can you connect me to rohit's wifi network", features.getNormalizedQuery());
        assertFalse(features.getTokens().isEmpty());
    }

    @Test
    public void testPreprocessorWifiNormalization() {
        assertEquals("open wifi settings", QueryPreprocessor.preprocess("Open Wi-Fi settings").getNormalizedQuery());
        assertEquals("connect to wifi", QueryPreprocessor.preprocess("connect to wi fi").getNormalizedQuery());
        assertEquals("turn on wifi", QueryPreprocessor.preprocess("TURN ON WI-FI").getNormalizedQuery());
        assertEquals("wifi network", QueryPreprocessor.preprocess("wifi network").getNormalizedQuery());
    }

    @Test
    public void testPreprocessorWhitespaceAndPunctuation() {
        QueryFeatures features = QueryPreprocessor.preprocess("   Set   brightness   to   50%   now!!!   ");
        assertEquals("set brightness to 50% now", features.getNormalizedQuery());
        List<String> expectedTokens = Arrays.asList("set", "brightness", "to", "50%", "now");
        assertEquals(expectedTokens, features.getTokens());
    }

    @Test
    public void testIntentDefinitionModel() {
        Map<String, IntentDefinition.ParameterSpec> params = Collections.singletonMap(
                "network_name",
                new IntentDefinition.ParameterSpec(IntentDefinition.ParameterType.STRING, true, "Target network SSID")
        );

        IntentDefinition def = new IntentDefinition(
                "CONNECT_WIFI",
                "Network",
                "Connect to a specified Wi-Fi network",
                Arrays.asList("connect", "join"),
                Arrays.asList("wifi", "wireless network", "wlan"),
                Arrays.asList("Connect to my Wi-Fi", "Join my wireless network"),
                params
        );

        assertEquals("CONNECT_WIFI", def.getIntentName());
        assertEquals("Network", def.getCategory());
        assertTrue(def.hasRequiredParameters());
        assertTrue(def.hasParameter("network_name"));
        assertEquals(2, def.getActionAliases().size());
        assertEquals(3, def.getObjectAliases().size());
    }

    @Test
    public void testExtractedParameterAndQueryFeatures() {
        ExtractedParameter param = new ExtractedParameter("network_name", "Rohit's Wi-Fi", IntentDefinition.ParameterType.STRING, 0.95);
        assertEquals("network_name", param.getName());
        assertEquals("Rohit's Wi-Fi", param.getValue());
        assertTrue(param.isValid());

        QueryFeatures features = new QueryFeatures("Connect to Rohit", "connect to rohit", Arrays.asList("connect", "to", "rohit"))
                .copyWithAction("connect")
                .copyWithObject("wifi")
                .copyWithParameters(Collections.singletonList(param));

        assertEquals("connect", features.getDetectedAction());
        assertEquals("wifi", features.getDetectedObject());
        assertTrue(features.hasParameter("network_name"));
        assertEquals("Rohit's Wi-Fi", features.getParameterValue("network_name"));
    }

    @Test
    public void testIntentMatchResultConstructors() {
        UserIntent userIntent = new UserIntent("CONNECT_WIFI", "Connect to Wi-Fi", "Network", Collections.singletonMap("network_name", "Home"));

        IntentMatchResult exact = IntentMatchResult.exactMatch(userIntent);
        assertTrue(exact.isConfident());
        assertEquals(IntentMatchResult.MatchSource.LOCAL_EXACT, exact.getMatchSource());
        assertEquals(1.0, exact.getConfidence(), 0.001);

        IntentMatchResult hybrid = IntentMatchResult.hybridMatch(userIntent, 0.88, Collections.singletonMap("CONNECT_WIFI", 0.88));
        assertTrue(hybrid.isConfident());
        assertEquals(IntentMatchResult.MatchSource.LOCAL_HYBRID, hybrid.getMatchSource());

        IntentMatchResult reject = IntentMatchResult.reject("some random query", "Score below threshold", Collections.emptyMap());
        assertFalse(reject.isConfident());
        assertTrue(reject.isWasRejected());
        assertEquals("Score below threshold", reject.getRejectionReason());
    }

    @Test
    public void testScoringWeightsDefaults() {
        ScoringWeights weights = ScoringWeights.defaultWeights();
        assertEquals(0.25, weights.getWLexical(), 0.001);
        assertEquals(0.20, weights.getWSemantic(), 0.001);
        assertEquals(0.20, weights.getWAction(), 0.001);
        assertEquals(0.15, weights.getWObject(), 0.001);
        assertEquals(0.12, weights.getWParameter(), 0.001);
        assertEquals(0.30, weights.getWContradiction(), 0.001);
        assertEquals(0.60, weights.getMinConfidence(), 0.001);
        assertEquals(0.15, weights.getMinMargin(), 0.001);
        assertEquals(10, weights.getTopK());
    }
}
