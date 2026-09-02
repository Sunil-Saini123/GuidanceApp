package com.example.floatingassistant.intent.retrieval;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CandidateRetriever — Combines BM25 lexical retrieval with Semantic retrieval
 * to filter hundreds of possible intents down to a high-precision Top-K candidate list.
 */
public class CandidateRetriever {

    public static class ScoredCandidate {
        private final IntentDefinition definition;
        private final double combinedScore;
        private final double bm25Score;
        private final double semanticScore;

        public ScoredCandidate(IntentDefinition definition, double combinedScore, double bm25Score, double semanticScore) {
            this.definition = definition;
            this.combinedScore = combinedScore;
            this.bm25Score = bm25Score;
            this.semanticScore = semanticScore;
        }

        public IntentDefinition getDefinition() {
            return definition;
        }

        public String getIntentName() {
            return definition.getIntentName();
        }

        public double getCombinedScore() {
            return combinedScore;
        }

        public double getBm25Score() {
            return bm25Score;
        }

        public double getSemanticScore() {
            return semanticScore;
        }

        @Override
        public String toString() {
            return "ScoredCandidate{" +
                    "intent=" + definition.getIntentName() +
                    ", score=" + String.format("%.3f", combinedScore) +
                    ", bm25=" + String.format("%.3f", bm25Score) +
                    ", sem=" + String.format("%.3f", semanticScore) +
                    '}';
        }
    }

    private final IntentCatalog catalog;
    private final BM25Retriever bm25Retriever;
    private final SemanticRetrievalStrategy semanticRetriever;

    public CandidateRetriever(IntentCatalog catalog, SemanticRetrievalStrategy semanticStrategy) {
        this.catalog = catalog != null ? catalog : IntentCatalog.defaultCatalog();
        this.bm25Retriever = new BM25Retriever(this.catalog);

        if (semanticStrategy != null) {
            this.semanticRetriever = semanticStrategy;
        } else {
            SemanticRetriever defaultSem = new SemanticRetriever();
            defaultSem.buildCorpus(this.catalog.getAllIntents());
            this.semanticRetriever = defaultSem;
        }
    }

    public CandidateRetriever(IntentCatalog catalog) {
        this(catalog, null);
    }

    public CandidateRetriever() {
        this(IntentCatalog.defaultCatalog(), null);
    }

    public BM25Retriever getBm25Retriever() {
        return bm25Retriever;
    }

    public SemanticRetrievalStrategy getSemanticRetriever() {
        return semanticRetriever;
    }

    /**
     * Retrieves Top-K candidates ranked by combined BM25 + Semantic score.
     */
    public List<ScoredCandidate> retrieveCandidates(QueryFeatures features, ScoringWeights weights) {
        if (features == null || features.getTokens().isEmpty()) {
            return Collections.emptyList();
        }

        ScoringWeights cfg = weights != null ? weights : ScoringWeights.defaultWeights();
        List<IntentDefinition> allIntents = catalog.getAllIntents();

        // 1. Compute BM25 scores
        Map<String, Double> bm25Scores = bm25Retriever.scoreAll(features);

        // 2. Compute Semantic scores
        Map<String, Double> semanticScores = semanticRetriever.computeSemanticScores(features, allIntents);

        // 3. Combine scores
        double wBm25 = cfg.getBm25Weight();
        double wSem = cfg.getSemanticWeight();
        double totalWeight = wBm25 + wSem;
        if (totalWeight <= 0) totalWeight = 1.0;

        List<ScoredCandidate> candidates = new ArrayList<>();

        for (IntentDefinition def : allIntents) {
            String name = def.getIntentName();
            double bScore = bm25Scores.getOrDefault(name, 0.0);
            double sScore = semanticScores.getOrDefault(name, 0.0);
            double combined = (wBm25 * bScore + wSem * sScore) / totalWeight;

            candidates.add(new ScoredCandidate(def, combined, bScore, sScore));
        }

        // 4. Sort descending by combined score
        candidates.sort((a, b) -> Double.compare(b.getCombinedScore(), a.getCombinedScore()));

        // 5. Return Top-K
        int k = Math.min(cfg.getTopK(), candidates.size());
        return candidates.subList(0, k);
    }
}
