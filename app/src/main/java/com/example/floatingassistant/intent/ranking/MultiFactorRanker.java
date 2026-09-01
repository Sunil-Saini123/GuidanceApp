package com.example.floatingassistant.intent.ranking;

import com.example.floatingassistant.intent.analysis.ContradictionDetector;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.intent.retrieval.CandidateRetriever;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * MultiFactorRanker — Calculates multi-dimensional relevance scores for candidate intents,
 * balancing positive lexical, semantic, action, object, and parameter evidence against
 * explicit contradiction penalties.
 */
public class MultiFactorRanker {

    public static class RankedCandidate {
        private final IntentDefinition definition;
        private final double finalScore;
        private final Map<String, Double> factorBreakdown;
        private final ContradictionDetector.ContradictionReport contradictionReport;

        public RankedCandidate(IntentDefinition definition,
                               double finalScore,
                               Map<String, Double> factorBreakdown,
                               ContradictionDetector.ContradictionReport contradictionReport) {
            this.definition = definition;
            this.finalScore = Math.max(0.0, Math.min(1.0, finalScore));
            this.factorBreakdown = new HashMap<>(factorBreakdown);
            this.contradictionReport = contradictionReport;
        }

        public IntentDefinition getDefinition() {
            return definition;
        }

        public String getIntentName() {
            return definition.getIntentName();
        }

        public double getFinalScore() {
            return finalScore;
        }

        public Map<String, Double> getFactorBreakdown() {
            return Collections.unmodifiableMap(factorBreakdown);
        }

        public ContradictionDetector.ContradictionReport getContradictionReport() {
            return contradictionReport;
        }

        @Override
        public String toString() {
            return "RankedCandidate{" +
                    "intent=" + definition.getIntentName() +
                    ", score=" + String.format("%.3f", finalScore) +
                    ", breakdown=" + factorBreakdown +
                    '}';
        }
    }

    /**
     * Ranks a list of retrieved candidates using multi-factor scoring and contradiction penalties.
     */
    public static List<RankedCandidate> rank(QueryFeatures features,
                                             List<CandidateRetriever.ScoredCandidate> candidates,
                                             ScoringWeights weights) {
        if (features == null || candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        ScoringWeights cfg = weights != null ? weights : ScoringWeights.defaultWeights();
        List<RankedCandidate> ranked = new ArrayList<>();

        for (CandidateRetriever.ScoredCandidate candidate : candidates) {
            IntentDefinition def = candidate.getDefinition();

            double lexicalScore = candidate.getBm25Score();
            double semanticScore = candidate.getSemanticScore();
            double actionScore = computeActionScore(features.getDetectedAction(), def);
            double objectScore = computeObjectScore(features.getDetectedObject(), def);
            double paramScore = computeParameterScore(features, def);
            double exampleScore = computeExampleScore(features, def);
            double categoryScore = computeCategoryScore(features.getDetectedObject(), def);

            ContradictionDetector.ContradictionReport contradictions =
                    ContradictionDetector.detectContradictions(features, def);
            double contradictionPenalty = contradictions.getTotalPenalty();

            // Multi-factor weighted sum
            double rawScore = (cfg.getWLexical() * lexicalScore)
                    + (cfg.getWSemantic() * semanticScore)
                    + (cfg.getWAction() * actionScore)
                    + (cfg.getWObject() * objectScore)
                    + (cfg.getWParameter() * paramScore)
                    + (cfg.getWExample() * exampleScore)
                    + (cfg.getWCategory() * categoryScore)
                    - (cfg.getWContradiction() * contradictionPenalty);

            // Factor breakdown for explainability & debugging
            Map<String, Double> breakdown = new HashMap<>();
            breakdown.put("lexical", lexicalScore);
            breakdown.put("semantic", semanticScore);
            breakdown.put("action", actionScore);
            breakdown.put("object", objectScore);
            breakdown.put("parameter", paramScore);
            breakdown.put("example", exampleScore);
            breakdown.put("category", categoryScore);
            breakdown.put("contradictionPenalty", contradictionPenalty);
            breakdown.put("rawFinal", rawScore);

            ranked.add(new RankedCandidate(def, rawScore, breakdown, contradictions));
        }

        // Sort descending by final score
        ranked.sort((a, b) -> Double.compare(b.getFinalScore(), a.getFinalScore()));

        return ranked;
    }

    private static double computeActionScore(String queryAction, IntentDefinition def) {
        if (queryAction.isEmpty()) return 0.2; // Neutral if no explicit action found
        String queryActionLower = queryAction.toLowerCase(Locale.US);

        for (String alias : def.getActionAliases()) {
            if (alias.equalsIgnoreCase(queryActionLower) || alias.contains(queryActionLower)) {
                return 1.0;
            }
        }
        if (def.getIntentName().toLowerCase(Locale.US).startsWith(queryActionLower)) {
            return 1.0;
        }
        return 0.0;
    }

    private static double computeObjectScore(String queryDomain, IntentDefinition def) {
        if (queryDomain.isEmpty()) return 0.2;
        String queryDomainLower = queryDomain.toLowerCase(Locale.US);

        for (String alias : def.getObjectAliases()) {
            if (alias.equalsIgnoreCase(queryDomainLower) || alias.contains(queryDomainLower) || queryDomainLower.contains(alias)) {
                return 1.0;
            }
        }
        if (def.getCategory().equalsIgnoreCase(queryDomainLower)) {
            return 0.8;
        }
        return 0.0;
    }

    private static double computeParameterScore(QueryFeatures features, IntentDefinition def) {
        boolean hasExtractedNet = features.hasParameter("network_name");
        boolean expectsNet = def.hasParameter("network_name");

        if (hasExtractedNet && expectsNet) {
            return 1.0; // Perfect parameter match
        }
        if (!hasExtractedNet && !def.hasRequiredParameters()) {
            return 0.8; // Both need no parameters
        }
        if (hasExtractedNet && !expectsNet) {
            return 0.0; // Incompatible
        }
        return 0.5;
    }

    private static double computeExampleScore(QueryFeatures features, IntentDefinition def) {
        String normalizedQuery = features.getNormalizedQuery();
        double bestSimilarity = 0.0;

        for (String example : def.getExamplePhrases()) {
            String normEx = QueryPreprocessor.preprocess(example).getNormalizedQuery();
            if (normEx.equals(normalizedQuery)) {
                return 1.0;
            }
            // Jaccard similarity between query tokens and example tokens
            List<String> qTokens = features.getTokens();
            List<String> exTokens = QueryPreprocessor.preprocess(example).getTokens();
            if (!qTokens.isEmpty() && !exTokens.isEmpty()) {
                int intersection = 0;
                for (String t : qTokens) {
                    if (exTokens.contains(t)) intersection++;
                }
                int union = qTokens.size() + exTokens.size() - intersection;
                double jaccard = union > 0 ? ((double) intersection / union) : 0.0;
                if (jaccard > bestSimilarity) bestSimilarity = jaccard;
            }
        }
        return bestSimilarity;
    }

    private static double computeCategoryScore(String queryDomain, IntentDefinition def) {
        if (queryDomain.isEmpty()) return 0.3;
        if (def.getCategory().equalsIgnoreCase(queryDomain) ||
                def.getCategory().toLowerCase(Locale.US).contains(queryDomain.toLowerCase(Locale.US))) {
            return 1.0;
        }
        return 0.0;
    }
}
