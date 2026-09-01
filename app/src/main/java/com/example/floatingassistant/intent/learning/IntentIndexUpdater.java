package com.example.floatingassistant.intent.learning;

import com.example.floatingassistant.intent.IntentClassificationEngine;
import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.retrieval.CandidateRetriever;
import com.example.floatingassistant.intent.retrieval.ExactMatcher;
import com.example.floatingassistant.intent.retrieval.SemanticRetrievalStrategy;
import com.example.floatingassistant.pathgenerator.AppLogger;

/**
 * IntentIndexUpdater — Propagates newly validated learning examples across
 * ALL THREE local retrieval indexes simultaneously:
 * 1. ExactMatcher phrase index
 * 2. BM25Retriever inverted index
 * 3. SemanticRetriever TF-IDF / vector index
 */
public class IntentIndexUpdater {

    private static final String TAG = "IntentIndexUpdater";

    public static void applyValidatedExample(IntentClassificationEngine engine, LearningStore.LearningRecord record) {
        if (engine == null || record == null || record.getStatus() != LearningStore.ValidationStatus.VALIDATED) {
            return;
        }

        String rawQuery = record.getRawQuery();
        String intentName = record.getIntentName();

        AppLogger.i(TAG, "🔄 UPDATING ALL THREE RETRIEVAL INDEXES with validated example: \"" + rawQuery + "\" -> " + intentName);

        // 1. Update Exact Matcher phrase index
        ExactMatcher exactMatcher = engine.getExactMatcher();
        if (exactMatcher != null) {
            exactMatcher.addLearnedPhrase(rawQuery, intentName);
        }

        // 2. Update Intent Catalog definition example list & rebuild BM25 index
        IntentCatalog catalog = engine.getCatalog();
        CandidateRetriever candidateRetriever = engine.getCandidateRetriever();

        if (catalog != null) {
            IntentDefinition existingDef = catalog.findById(intentName);
            if (existingDef != null) {
                existingDef.addExamplePhrase(rawQuery);
            }

            if (candidateRetriever != null && candidateRetriever.getBm25Retriever() != null) {
                candidateRetriever.getBm25Retriever().rebuildIndex();
            }
        }

        // 3. Update Semantic Retriever index (TF-IDF vector space)
        if (candidateRetriever != null && candidateRetriever.getSemanticRetriever() != null) {
            SemanticRetrievalStrategy semanticStrategy = candidateRetriever.getSemanticRetriever();
            semanticStrategy.addLearnedExample(rawQuery, intentName);
        }

        AppLogger.i(TAG, "✅ ALL 3 INDEXES SYNCHRONIZED (Exact, BM25, Semantic)");
    }
}
