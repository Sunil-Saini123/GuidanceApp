package com.example.floatingassistant.intent.retrieval;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BM25Retriever — Computes Okapi BM25 lexical relevance scores between query tokens
 * and indexed IntentDefinition documents.
 */
public class BM25Retriever {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private final IntentCatalog catalog;
    private final Map<String, List<String>> intentDocTokens = new HashMap<>();
    private final Map<String, Integer> docLengths = new HashMap<>();
    private final Map<String, Integer> docFrequencies = new HashMap<>();
    private double avgDocLength = 0.0;
    private int totalDocs = 0;

    public BM25Retriever(IntentCatalog catalog) {
        this.catalog = catalog != null ? catalog : IntentCatalog.defaultCatalog();
        rebuildIndex();
    }

    public synchronized void rebuildIndex() {
        intentDocTokens.clear();
        docLengths.clear();
        docFrequencies.clear();

        List<IntentDefinition> all = catalog.getAllIntents();
        totalDocs = all.size();
        if (totalDocs == 0) {
            avgDocLength = 0;
            return;
        }

        int totalLengthSum = 0;

        for (IntentDefinition def : all) {
            List<String> docTokens = buildDocTokens(def);
            String intentName = def.getIntentName();
            intentDocTokens.put(intentName, docTokens);
            int len = docTokens.size();
            docLengths.put(intentName, len);
            totalLengthSum += len;

            Set<String> uniqueTerms = new HashSet<>(docTokens);
            for (String term : uniqueTerms) {
                docFrequencies.put(term, docFrequencies.getOrDefault(term, 0) + 1);
            }
        }

        avgDocLength = (double) totalLengthSum / totalDocs;
    }

    private List<String> buildDocTokens(IntentDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append(def.getIntentName().replace("_", " ")).append(" ");
        sb.append(def.getCategory()).append(" ");
        sb.append(def.getDescription()).append(" ");
        for (String action : def.getActionAliases()) sb.append(action).append(" ");
        for (String obj : def.getObjectAliases()) sb.append(obj).append(" ");
        for (String ex : def.getExamplePhrases()) sb.append(ex).append(" ");
        for (String param : def.getParameters().keySet()) sb.append(param.replace("_", " ")).append(" ");

        return QueryPreprocessor.preprocess(sb.toString()).getTokens();
    }

    /**
     * Scores all catalog intents against the query tokens using Okapi BM25,
     * normalized to [0.0, 1.0].
     */
    public synchronized Map<String, Double> scoreAll(QueryFeatures features) {
        Map<String, Double> rawScores = new HashMap<>();
        if (features == null || features.getTokens().isEmpty() || totalDocs == 0) {
            return rawScores;
        }

        List<String> queryTokens = features.getTokens();
        double maxScore = 0.0;

        for (Map.Entry<String, List<String>> entry : intentDocTokens.entrySet()) {
            String intentName = entry.getKey();
            List<String> docTokens = entry.getValue();
            int docLen = docLengths.getOrDefault(intentName, 0);

            // Compute term frequencies in this document
            Map<String, Integer> tf = new HashMap<>();
            for (String token : docTokens) {
                tf.put(token, tf.getOrDefault(token, 0) + 1);
            }

            double score = 0.0;
            for (String qTerm : queryTokens) {
                int freqInDoc = tf.getOrDefault(qTerm, 0);
                if (freqInDoc > 0) {
                    int df = docFrequencies.getOrDefault(qTerm, 1);
                    double idf = Math.log(1.0 + (totalDocs - df + 0.5) / (df + 0.5));
                    if (idf < 0) idf = 0.01;

                    double numerator = freqInDoc * (K1 + 1.0);
                    double denominator = freqInDoc + K1 * (1.0 - B + B * (avgDocLength > 0 ? (docLen / avgDocLength) : 1.0));
                    score += idf * (numerator / denominator);
                }
            }

            rawScores.put(intentName, score);
            if (score > maxScore) {
                maxScore = score;
            }
        }

        // Normalize scores to [0.0, 1.0]
        Map<String, Double> normalized = new HashMap<>();
        for (Map.Entry<String, Double> entry : rawScores.entrySet()) {
            double norm = maxScore > 0 ? (entry.getValue() / maxScore) : 0.0;
            normalized.put(entry.getKey(), norm);
        }

        return normalized;
    }
}
