package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.fallback.GroqFallbackHandler;
import com.example.floatingassistant.intent.learning.LearningStore;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.pathgenerator.GroqProxyClient;
import com.example.floatingassistant.pathgenerator.PathRequest;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage5Test — Verifies Groq Fallback Handler, Structured JSON parsing,
 * and 3-index adaptive learning loop.
 */
public class Stage5Test {

    private IntentCatalog catalog;
    private IntentClassificationEngine engine;

    @Before
    public void setUp() {
        catalog = IntentCatalog.defaultCatalog();
        engine = new IntentClassificationEngine(catalog, ScoringWeights.defaultWeights());
    }

    @Test
    public void testGroqStructuredResponseParsing() {
        String structuredJson = "{\n" +
                "  \"intentName\": \"CONNECT_WIFI\",\n" +
                "  \"category\": \"Network\",\n" +
                "  \"parameters\": {\n" +
                "    \"network_name\": \"Office 5G\"\n" +
                "  },\n" +
                "  \"confidence\": 0.96\n" +
                "}";

        IntentMatchResult result = GroqFallbackHandler.parseGroqResponse("Connect to Office 5G", structuredJson);

        assertNotNull(result);
        assertTrue(result.isConfident());
        assertEquals(IntentMatchResult.MatchSource.GROQ_FALLBACK, result.getMatchSource());
        assertEquals("CONNECT_WIFI", result.getUserIntent().getIntentName());
        assertEquals("Network", result.getUserIntent().getTargetCategory());
        assertEquals("Office 5G", result.getUserIntent().getParameters().get("network_name"));
    }

    @Test
    public void testGroqUnknownResponseRejection() {
        String unknownJson = "{\"intentName\": \"UNKNOWN\", \"confidence\": 0.0}";
        IntentMatchResult result = GroqFallbackHandler.parseGroqResponse("what is quantum computing", unknownJson);

        assertNotNull(result);
        assertFalse(result.isConfident());
        assertTrue(result.isWasRejected());
    }

    @Test
    public void testAdaptiveLearningUpdatesAllThreeIndexes() {
        // Query that is not initially in exact or catalog examples
        String novelQuery = "pair up with my studio wireless sound bar";

        // Step 1: Query is unknown / uncertain without fallback
        engine.setEnableGroqFallback(false);
        IntentMatchResult beforeResult = engine.classify(novelQuery);
        assertFalse(beforeResult.isConfident());

        // Step 2: Inject mock Groq client that resolves the intent
        GroqProxyClient mockGroqClient = new GroqProxyClient() {
            @Override
            public String sendRequest(PathRequest request) throws Exception {
                return "{\n" +
                        "  \"intentName\": \"ENABLE_BLUETOOTH\",\n" +
                        "  \"category\": \"Connected Devices\",\n" +
                        "  \"confidence\": 0.95\n" +
                        "}";
            }
        };

        GroqFallbackHandler fallbackHandler = new GroqFallbackHandler(mockGroqClient, catalog);
        IntentClassificationEngine learningEngine = new IntentClassificationEngine(
                catalog,
                ScoringWeights.defaultWeights(),
                fallbackHandler,
                new LearningStore()
        );
        learningEngine.setEnableGroqFallback(true);

        // Step 3: Classify with Groq fallback enabled -> Groq resolves it & triggers index updates
        IntentMatchResult fallbackResult = learningEngine.classify(novelQuery);
        assertTrue(fallbackResult.isConfident());
        assertEquals(IntentMatchResult.MatchSource.GROQ_FALLBACK, fallbackResult.getMatchSource());
        assertEquals("ENABLE_BLUETOOTH", fallbackResult.getUserIntent().getIntentName());

        // Step 4: Classify the SAME query again -> should now hit EXACT MATCH instantly without Groq!
        IntentMatchResult secondRun = learningEngine.classify(novelQuery);
        assertTrue(secondRun.isConfident());
        assertEquals("Subsequent query must hit LOCAL_EXACT immediately",
                IntentMatchResult.MatchSource.LOCAL_EXACT, secondRun.getMatchSource());
        assertEquals("ENABLE_BLUETOOTH", secondRun.getUserIntent().getIntentName());
    }
}
