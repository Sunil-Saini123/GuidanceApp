package com.example.floatingassistant.intent.retrieval;

import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.List;
import java.util.Map;

/**
 * SemanticRetrievalStrategy — Pluggable interface for semantic intent retrieval.
 * Implementations can range from TF-IDF cosine similarity to local neural embeddings (ONNX/TFLite).
 */
public interface SemanticRetrievalStrategy {

    /**
     * Computes semantic similarity scores for all candidate intents against the query,
     * normalized in range [0.0, 1.0].
     */
    Map<String, Double> computeSemanticScores(QueryFeatures features, List<IntentDefinition> candidates);

    /**
     * Updates the semantic index when a new validated learning example is added.
     */
    void addLearnedExample(String exampleQuery, String intentName);
}
