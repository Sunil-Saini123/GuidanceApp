package com.example.floatingassistant.intent.evaluation;

import com.example.floatingassistant.intent.IntentClassificationEngine;
import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.ScoringWeights;

import java.util.List;

/**
 * EvaluationRunner — Evaluates the IntentClassificationEngine against a dataset
 * and computes AccuracyMetrics.
 */
public class EvaluationRunner {

    public static AccuracyMetrics runEvaluation(IntentClassificationEngine engine, List<IntentEvaluationDataset.TestCase> dataset) {
        if (engine == null || dataset == null || dataset.isEmpty()) {
            return new AccuracyMetrics(0, 0, 0, 0, 0);
        }

        int correct = 0;
        int falsePositives = 0;
        int correctRejections = 0;
        int uncertain = 0;

        for (IntentEvaluationDataset.TestCase testCase : dataset) {
            String query = testCase.getQuery();
            String expected = testCase.getExpectedIntent();
            boolean expectReject = testCase.isExpectReject();

            IntentMatchResult result = engine.classify(query);

            if (expectReject) {
                if (result.isWasRejected() || !result.isConfident()) {
                    correctRejections++;
                } else {
                    falsePositives++;
                }
            } else {
                if (result.isConfident() && expected.equalsIgnoreCase(result.getUserIntent().getIntentName())) {
                    correct++;
                } else if (result.isWasRejected() || !result.isConfident()) {
                    uncertain++;
                } else {
                    falsePositives++;
                }
            }
        }

        return new AccuracyMetrics(dataset.size(), correct, falsePositives, correctRejections, uncertain);
    }

    public static AccuracyMetrics runBenchmark(ScoringWeights weights) {
        IntentClassificationEngine engine = new IntentClassificationEngine(IntentCatalog.defaultCatalog(), weights);
        return runEvaluation(engine, IntentEvaluationDataset.getBenchmarkCases());
    }
}
