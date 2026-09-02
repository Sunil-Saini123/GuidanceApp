package com.example.floatingassistant.intent.retrieval;

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
 * SemanticRetriever — TF-IDF Vector Space Model baseline implementation of SemanticRetrievalStrategy.
 * Computes cosine similarity between TF-IDF vectors of query and intent descriptions/aliases/examples.
 * Supports online index updates via addLearnedExample.
 */
public class SemanticRetriever implements SemanticRetrievalStrategy {

    private final Map<String, List<String>> intentCorpus = new HashMap<>();
    private final Map<String, Double> vocabularyIdf = new HashMap<>();
    private final Map<String, Map<String, Double>> tfidfVectors = new HashMap<>();
    private final Map<String, Double> vectorMagnitudes = new HashMap<>();

    public SemanticRetriever() {
    }

    public synchronized void buildCorpus(List<IntentDefinition> definitions) {
        intentCorpus.clear();
        for (IntentDefinition def : definitions) {
            List<String> tokens = extractTokens(def);
            intentCorpus.put(def.getIntentName(), tokens);
        }
        recomputeTfIdfVectors();
    }

    @Override
    public synchronized void addLearnedExample(String exampleQuery, String intentName) {
        if (exampleQuery == null || intentName == null) return;
        List<String> newTokens = QueryPreprocessor.preprocess(exampleQuery).getTokens();
        List<String> existing = intentCorpus.getOrDefault(intentName.toUpperCase(), new ArrayList<>());
        List<String> merged = new ArrayList<>(existing);
        merged.addAll(newTokens);
        intentCorpus.put(intentName.toUpperCase(), merged);
        recomputeTfIdfVectors();
    }

    private synchronized void recomputeTfIdfVectors() {
        vocabularyIdf.clear();
        tfidfVectors.clear();
        vectorMagnitudes.clear();

        int totalDocs = intentCorpus.size();
        if (totalDocs == 0) return;

        // 1. Calculate Document Frequency (DF) for each unique term
        Map<String, Integer> docFrequencies = new HashMap<>();
        for (List<String> docTokens : intentCorpus.values()) {
            Set<String> uniqueTerms = new HashSet<>(docTokens);
            for (String term : uniqueTerms) {
                docFrequencies.put(term, docFrequencies.getOrDefault(term, 0) + 1);
            }
        }

        // 2. Calculate Inverse Document Frequency (IDF)
        for (Map.Entry<String, Integer> entry : docFrequencies.entrySet()) {
            double idf = Math.log(1.0 + ((double) totalDocs / (entry.getValue() + 1.0))) + 1.0;
            vocabularyIdf.put(entry.getKey(), idf);
        }

        // 3. Calculate TF-IDF vectors and magnitudes
        for (Map.Entry<String, List<String>> entry : intentCorpus.entrySet()) {
            String intentName = entry.getKey();
            List<String> docTokens = entry.getValue();

            Map<String, Integer> termFreqs = new HashMap<>();
            for (String term : docTokens) {
                termFreqs.put(term, termFreqs.getOrDefault(term, 0) + 1);
            }

            Map<String, Double> vector = new HashMap<>();
            double magSquared = 0.0;

            for (Map.Entry<String, Integer> tfEntry : termFreqs.entrySet()) {
                String term = tfEntry.getKey();
                double tf = 1.0 + Math.log(tfEntry.getValue());
                double idf = vocabularyIdf.getOrDefault(term, 1.0);
                double weight = tf * idf;
                vector.put(term, weight);
                magSquared += weight * weight;
            }

            tfidfVectors.put(intentName, vector);
            vectorMagnitudes.put(intentName, Math.sqrt(magSquared));
        }
    }

    @Override
    public synchronized Map<String, Double> computeSemanticScores(QueryFeatures features, List<IntentDefinition> candidates) {
        Map<String, Double> scores = new HashMap<>();
        if (features == null || features.getTokens().isEmpty() || tfidfVectors.isEmpty()) {
            return scores;
        }

        // 1. Build Query TF-IDF vector
        Map<String, Integer> qTermFreqs = new HashMap<>();
        for (String token : features.getTokens()) {
            qTermFreqs.put(token, qTermFreqs.getOrDefault(token, 0) + 1);
        }

        Map<String, Double> qVector = new HashMap<>();
        double qMagSquared = 0.0;

        for (Map.Entry<String, Integer> entry : qTermFreqs.entrySet()) {
            String term = entry.getKey();
            double tf = 1.0 + Math.log(entry.getValue());
            double idf = vocabularyIdf.getOrDefault(term, 0.5);
            double weight = tf * idf;
            qVector.put(term, weight);
            qMagSquared += weight * weight;
        }

        double qMagnitude = Math.sqrt(qMagSquared);
        if (qMagnitude == 0.0) return scores;

        // 2. Compute Cosine Similarity with candidates
        for (IntentDefinition def : candidates) {
            String intentName = def.getIntentName();
            Map<String, Double> docVector = tfidfVectors.get(intentName);
            Double docMag = vectorMagnitudes.get(intentName);

            if (docVector == null || docMag == null || docMag == 0.0) {
                scores.put(intentName, 0.0);
                continue;
            }

            double dotProduct = 0.0;
            for (Map.Entry<String, Double> qEntry : qVector.entrySet()) {
                Double docWeight = docVector.get(qEntry.getKey());
                if (docWeight != null) {
                    dotProduct += qEntry.getValue() * docWeight;
                }
            }

            double cosineSim = dotProduct / (qMagnitude * docMag);
            scores.put(intentName, Math.max(0.0, Math.min(1.0, cosineSim)));
        }

        return scores;
    }

    private List<String> extractTokens(IntentDefinition def) {
        StringBuilder sb = new StringBuilder();
        sb.append(def.getIntentName().replace("_", " ")).append(" ");
        sb.append(def.getDescription()).append(" ");
        for (String action : def.getActionAliases()) sb.append(action).append(" ");
        for (String obj : def.getObjectAliases()) sb.append(obj).append(" ");
        for (String ex : def.getExamplePhrases()) sb.append(ex).append(" ");
        return QueryPreprocessor.preprocess(sb.toString()).getTokens();
    }
}
