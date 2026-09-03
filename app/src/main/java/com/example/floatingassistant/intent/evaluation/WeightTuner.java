package com.example.floatingassistant.intent.evaluation;

import com.example.floatingassistant.intent.model.ScoringWeights;

import java.util.ArrayList;
import java.util.List;

/**
 * WeightTuner — Evaluates multiple weight/threshold configurations to identify
 * the optimal parameter settings for local classification accuracy and safety.
 */
public class WeightTuner {

    public static class TuningResult {
        private final String configName;
        private final ScoringWeights weights;
        private final AccuracyMetrics metrics;

        public TuningResult(String configName, ScoringWeights weights, AccuracyMetrics metrics) {
            this.configName = configName;
            this.weights = weights;
            this.metrics = metrics;
        }

        public String getConfigName() { return configName; }
        public ScoringWeights getWeights() { return weights; }
        public AccuracyMetrics getMetrics() { return metrics; }

        @Override
        public String toString() {
            return String.format("[%s] -> Accuracy: %.1f%% | FalsePositives: %d | FallbackRate: %.1f%%",
                    configName, metrics.getOverallAccuracy() * 100.0, metrics.getFalsePositives(), metrics.getFallbackRate() * 100.0);
        }
    }

    /**
     * Compares multiple preset configurations on the benchmark dataset.
     */
    public static List<TuningResult> runPresetComparison() {
        List<TuningResult> results = new ArrayList<>();

        // Config A: Default balanced weights
        ScoringWeights configA = ScoringWeights.defaultWeights();
        results.add(new TuningResult("Config A (Balanced Default)", configA, EvaluationRunner.runBenchmark(configA)));

        // Config B: Higher Lexical / BM25 focus
        ScoringWeights configB = new ScoringWeights();
        configB.setWLexical(0.35);
        configB.setWSemantic(0.15);
        configB.setBm25Weight(0.80);
        configB.setSemanticWeight(0.20);
        results.add(new TuningResult("Config B (Lexical-Heavy)", configB, EvaluationRunner.runBenchmark(configB)));

        // Config C: Higher Action / Parameter weight with strong contradiction penalty
        ScoringWeights configC = new ScoringWeights();
        configC.setWAction(0.25);
        configC.setWParameter(0.20);
        configC.setWContradiction(0.40);
        configC.setMinConfidence(0.55);
        results.add(new TuningResult("Config C (Action/Param Contradiction-Aware)", configC, EvaluationRunner.runBenchmark(configC)));

        return results;
    }
}
