package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.analysis.ActionExtractor;
import com.example.floatingassistant.intent.analysis.ContradictionDetector;
import com.example.floatingassistant.intent.analysis.ObjectExtractor;
import com.example.floatingassistant.intent.analysis.ParameterExtractor;
import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.evaluation.AccuracyMetrics;
import com.example.floatingassistant.intent.evaluation.EvaluationRunner;
import com.example.floatingassistant.intent.evaluation.IntentEvaluationDataset;
import com.example.floatingassistant.intent.evaluation.WeightTuner;
import com.example.floatingassistant.intent.model.ExtractedParameter;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Stage4Test — Verifies Feature Extraction, Contradiction Detection,
 * Multi-factor Scoring, Confidence Gate, and Evaluation Metrics.
 */
public class Stage4Test {

    private IntentCatalog catalog;
    private IntentClassificationEngine engine;

    @Before
    public void setUp() {
        catalog = IntentCatalog.defaultCatalog();
        engine = new IntentClassificationEngine(catalog, ScoringWeights.defaultWeights());
    }

    @Test
    public void testConnectWifiDistinction() {
        // Query has action CONNECT, object WIFI, parameter network_name
        String query = "Connect to Rohit's wireless network";
        IntentMatchResult result = engine.classify(query);

        assertNotNull(result);
        assertTrue(result.isConfident());
        assertEquals("CONNECT_WIFI", result.getUserIntent().getIntentName());
        assertFalse("Must NOT match ENABLE_WIFI", "ENABLE_WIFI".equals(result.getUserIntent().getIntentName()));
        assertFalse("Must NOT match OPEN_WIFI_SETTINGS", "OPEN_WIFI_SETTINGS".equals(result.getUserIntent().getIntentName()));
    }

    @Test
    public void testContradictionPenaltyOnEnableWifiForNamedNetwork() {
        QueryFeatures features = QueryPreprocessor.preprocess("Connect to Rohit's wireless network");
        String action = ActionExtractor.extractAction(features);
        String object = new ObjectExtractor(catalog).extractObject(features);
        List<ExtractedParameter> params = ParameterExtractor.extractParameters(features);

        QueryFeatures enriched = features.copyWithAction(action).copyWithObject(object).copyWithParameters(params);

        IntentDefinition enableWifi = catalog.findById("ENABLE_WIFI");
        IntentDefinition connectWifi = catalog.findById("CONNECT_WIFI");

        ContradictionDetector.ContradictionReport enableReport = ContradictionDetector.detectContradictions(enriched, enableWifi);
        ContradictionDetector.ContradictionReport connectReport = ContradictionDetector.detectContradictions(enriched, connectWifi);

        // ENABLE_WIFI should receive strong action & parameter contradiction penalties
        assertTrue("ENABLE_WIFI must receive a strong contradiction penalty", enableReport.getTotalPenalty() > 0.5);
        assertEquals("CONNECT_WIFI must have 0 contradiction penalty", 0.0, connectReport.getTotalPenalty(), 0.01);
    }

    @Test
    public void testDisplayBrightnessMatch() {
        IntentMatchResult result = engine.classify("Set brightness to 50%");
        assertNotNull(result);
        assertTrue(result.isConfident());
        assertEquals("OPEN_DISPLAY_SETTINGS", result.getUserIntent().getIntentName());
    }

    @Test
    public void testOutOfDomainRejection() {
        // Query out of domain should be rejected
        IntentMatchResult result = engine.classify("Order pizza from dominos");
        assertNotNull(result);
        assertFalse("Out of domain query must NOT be accepted as a confident match", result.isConfident());
        assertTrue(result.isWasRejected());
    }

    @Test
    public void testEvaluationRunnerBenchmark() {
        AccuracyMetrics metrics = EvaluationRunner.runBenchmark(ScoringWeights.defaultWeights());
        assertNotNull(metrics);
        System.out.println("Benchmark Results: " + metrics);

        // We expect high overall accuracy (> 85%) and low false positive rate (< 5%)
        assertTrue("Overall accuracy must exceed 85%", metrics.getOverallAccuracy() >= 0.85);
        assertTrue("False positive rate must be under 5%", metrics.getFalsePositiveRate() <= 0.05);
    }

    @Test
    public void testWeightTunerPresets() {
        List<WeightTuner.TuningResult> results = WeightTuner.runPresetComparison();
        assertNotNull(results);
        assertEquals(3, results.size());
        for (WeightTuner.TuningResult res : results) {
            System.out.println("Tuned Config: " + res);
            assertTrue(res.getMetrics().getOverallAccuracy() > 0.80);
        }
    }
}
