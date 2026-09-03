package com.example.floatingassistant.intent.ranking;

import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.pathgenerator.UserIntent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ConfidenceEvaluator — Gatekeeper applying absolute confidence, top-1/top-2 margin,
 * and parameter validity checks to determine whether to accept the top prediction or reject/route to Groq.
 */
public class ConfidenceEvaluator {

    public static IntentMatchResult evaluate(QueryFeatures features,
                                             List<MultiFactorRanker.RankedCandidate> rankedList,
                                             ScoringWeights weights) {
        if (features == null || rankedList == null || rankedList.isEmpty()) {
            return IntentMatchResult.reject(
                    features != null ? features.getRawQuery() : "",
                    "No candidates retrieved",
                    null
            );
        }

        ScoringWeights cfg = weights != null ? weights : ScoringWeights.defaultWeights();

        Map<String, Double> scoreMap = new HashMap<>();
        for (MultiFactorRanker.RankedCandidate rc : rankedList) {
            scoreMap.put(rc.getIntentName(), rc.getFinalScore());
        }

        MultiFactorRanker.RankedCandidate top1 = rankedList.get(0);
        double top1Score = top1.getFinalScore();

        // 1. Check Absolute Score Threshold
        if (top1Score < cfg.getMinConfidence()) {
            return IntentMatchResult.reject(
                    features.getRawQuery(),
                    String.format("Top score (%.3f) below minimum confidence threshold (%.3f)", top1Score, cfg.getMinConfidence()),
                    scoreMap
            );
        }

        // 2. Check Winner Margin Threshold (Top-1 vs Top-2)
        if (rankedList.size() > 1) {
            MultiFactorRanker.RankedCandidate top2 = rankedList.get(1);
            double margin = top1Score - top2.getFinalScore();
            if (margin < cfg.getMinMargin()) {
                return IntentMatchResult.reject(
                        features.getRawQuery(),
                        String.format("Ambiguous match: margin (%.3f) between top-1 (%s: %.3f) and top-2 (%s: %.3f) below margin threshold (%.3f)",
                                margin, top1.getIntentName(), top1Score, top2.getIntentName(), top2.getFinalScore(), cfg.getMinMargin()),
                        scoreMap
                );
            }
        }

        // 3. Check Parameter Validity & Contradictions
        if (top1.getContradictionReport().getTotalPenalty() > 0.40) {
            return IntentMatchResult.reject(
                    features.getRawQuery(),
                    "Top candidate contains severe semantic or parameter contradiction (" + top1.getContradictionReport() + ")",
                    scoreMap
            );
        }

        // Check required parameter fulfillment
        IntentDefinition def = top1.getDefinition();
        for (Map.Entry<String, IntentDefinition.ParameterSpec> paramEntry : def.getParameters().entrySet()) {
            String paramName = paramEntry.getKey();
            IntentDefinition.ParameterSpec spec = paramEntry.getValue();
            if (spec.isRequired() && !features.hasParameter(paramName)) {
                return IntentMatchResult.reject(
                        features.getRawQuery(),
                        "Missing required parameter '" + paramName + "' for intent " + def.getIntentName(),
                        scoreMap
                );
            }
        }

        // All checks passed -> Confident Match
        UserIntent userIntent = new UserIntent(
                def.getIntentName(),
                features.getRawQuery(),
                def.getCategory(),
                features.getParametersAsMap()
        );

        return IntentMatchResult.hybridMatch(userIntent, top1Score, scoreMap);
    }
}
