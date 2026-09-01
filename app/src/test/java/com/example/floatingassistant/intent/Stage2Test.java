package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.intent.retrieval.ExactMatcher;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage2Test — Verifies IntentCatalog registry and ExactMatcher lookups.
 */
public class Stage2Test {

    private IntentCatalog catalog;
    private ExactMatcher exactMatcher;

    @Before
    public void setUp() {
        catalog = IntentCatalog.defaultCatalog();
        exactMatcher = new ExactMatcher(catalog);
    }

    @Test
    public void testCatalogDefaults() {
        assertTrue(catalog.size() >= 10);

        IntentDefinition connectWifi = catalog.findById("CONNECT_WIFI");
        assertNotNull(connectWifi);
        assertEquals("Network", connectWifi.getCategory());
        assertTrue(connectWifi.hasParameter("network_name"));

        List<IntentDefinition> networkIntents = catalog.getByCategory("Network");
        assertTrue(networkIntents.size() >= 4);
    }

    @Test
    public void testExactIntentNameMatch() {
        QueryFeatures f1 = QueryPreprocessor.preprocess("ENABLE_BLUETOOTH");
        IntentMatchResult r1 = exactMatcher.match(f1);
        assertNotNull(r1);
        assertTrue(r1.isConfident());
        assertEquals(IntentMatchResult.MatchSource.LOCAL_EXACT, r1.getMatchSource());
        assertEquals("ENABLE_BLUETOOTH", r1.getUserIntent().getIntentName());

        QueryFeatures f2 = QueryPreprocessor.preprocess("enable_wifi");
        IntentMatchResult r2 = exactMatcher.match(f2);
        assertNotNull(r2);
        assertEquals("ENABLE_WIFI", r2.getUserIntent().getIntentName());
    }

    @Test
    public void testExactExamplePhraseMatch() {
        QueryFeatures f1 = QueryPreprocessor.preprocess("Turn on Wi-Fi");
        IntentMatchResult r1 = exactMatcher.match(f1);
        assertNotNull(r1);
        assertEquals("ENABLE_WIFI", r1.getUserIntent().getIntentName());

        QueryFeatures f2 = QueryPreprocessor.preprocess("Open Wi-Fi settings");
        IntentMatchResult r2 = exactMatcher.match(f2);
        assertNotNull(r2);
        assertEquals("OPEN_WIFI_SETTINGS", r2.getUserIntent().getIntentName());

        QueryFeatures f3 = QueryPreprocessor.preprocess("Change wallpaper");
        IntentMatchResult r3 = exactMatcher.match(f3);
        assertNotNull(r3);
        assertEquals("CHANGE_WALLPAPER", r3.getUserIntent().getIntentName());
    }

    @Test
    public void testLearnedPhraseMatch() {
        String customPhrase = "connect me to home fiber network";
        QueryFeatures f = QueryPreprocessor.preprocess(customPhrase);

        // Before learning: no exact match
        assertNull(exactMatcher.match(f));

        // Learn the phrase
        exactMatcher.addLearnedPhrase(customPhrase, "CONNECT_WIFI");
        assertTrue(exactMatcher.hasLearnedPhrase(customPhrase));

        // After learning: exact match succeeds
        IntentMatchResult r = exactMatcher.match(f);
        assertNotNull(r);
        assertEquals("CONNECT_WIFI", r.getUserIntent().getIntentName());
        assertEquals(IntentMatchResult.MatchSource.LOCAL_EXACT, r.getMatchSource());
    }

    @Test
    public void testNonExactQueryReturnsNull() {
        QueryFeatures f = QueryPreprocessor.preprocess("Can you connect to Rohit's wifi wireless network please");
        IntentMatchResult r = exactMatcher.match(f);
        assertNull(r); // Must fall through to Candidate Retrieval & Multi-factor ranking
    }
}
