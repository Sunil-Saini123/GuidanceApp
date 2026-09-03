package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.intent.retrieval.BM25Retriever;
import com.example.floatingassistant.intent.retrieval.CandidateRetriever;
import com.example.floatingassistant.intent.retrieval.SemanticRetriever;

import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage3Test — Verifies BM25, Semantic TF-IDF, and CandidateRetriever hybrid retrieval.
 */
public class Stage3Test {

    private IntentCatalog catalog;
    private CandidateRetriever candidateRetriever;

    @Before
    public void setUp() {
        catalog = IntentCatalog.defaultCatalog();
        candidateRetriever = new CandidateRetriever(catalog);
    }

    @Test
    public void testBM25Scoring() {
        BM25Retriever bm25 = candidateRetriever.getBm25Retriever();
        QueryFeatures features = QueryPreprocessor.preprocess("connect to wireless wifi network");
        Map<String, Double> scores = bm25.scoreAll(features);

        assertNotNull(scores);
        assertFalse(scores.isEmpty());
        assertTrue(scores.getOrDefault("CONNECT_WIFI", 0.0) > 0.3);
    }

    @Test
    public void testSemanticTfIdfCosineScoring() {
        SemanticRetriever sem = (SemanticRetriever) candidateRetriever.getSemanticRetriever();
        QueryFeatures features = QueryPreprocessor.preprocess("increase screen brightness");
        Map<String, Double> scores = sem.computeSemanticScores(features, catalog.getAllIntents());

        assertNotNull(scores);
        assertTrue(scores.getOrDefault("OPEN_DISPLAY_SETTINGS", 0.0) > 0.3);
    }

    @Test
    public void testCandidateRetrievalTopK() {
        QueryFeatures features = QueryPreprocessor.preprocess("Connect to Rohit's wireless network");
        List<CandidateRetriever.ScoredCandidate> topK = candidateRetriever.retrieveCandidates(features, ScoringWeights.defaultWeights());

        assertNotNull(topK);
        assertTrue(topK.size() <= 10);
        assertTrue(topK.size() >= 3);

        // CONNECT_WIFI should be in the top 3 candidates
        boolean connectWifiInTop3 = false;
        for (int i = 0; i < Math.min(3, topK.size()); i++) {
            if ("CONNECT_WIFI".equals(topK.get(i).getIntentName())) {
                connectWifiInTop3 = true;
                break;
            }
        }
        assertTrue("CONNECT_WIFI must be present in top 3 retrieved candidates", connectWifiInTop3);
    }

    @Test
    public void testDisplayBrightnessRetrieval() {
        QueryFeatures features = QueryPreprocessor.preprocess("increase brightness on screen");
        List<CandidateRetriever.ScoredCandidate> topK = candidateRetriever.retrieveCandidates(features, ScoringWeights.defaultWeights());

        assertEquals("OPEN_DISPLAY_SETTINGS", topK.get(0).getIntentName());
    }

    @Test
    public void testWeightsConfigurationImpact() {
        QueryFeatures features = QueryPreprocessor.preprocess("bluetooth connection");

        ScoringWeights highBm25 = new ScoringWeights();
        highBm25.setBm25Weight(1.0);
        highBm25.setSemanticWeight(0.0);
        List<CandidateRetriever.ScoredCandidate> resBm25 = candidateRetriever.retrieveCandidates(features, highBm25);

        ScoringWeights highSem = new ScoringWeights();
        highSem.setBm25Weight(0.0);
        highSem.setSemanticWeight(1.0);
        List<CandidateRetriever.ScoredCandidate> resSem = candidateRetriever.retrieveCandidates(features, highSem);

        assertNotNull(resBm25);
        assertNotNull(resSem);
        assertEquals("ENABLE_BLUETOOTH", resBm25.get(0).getIntentName());
    }
}
