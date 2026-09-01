package com.example.floatingassistant.intent.retrieval;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.pathgenerator.UserIntent;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ExactMatcher — Performs fast exact-match lookups before initiating multi-factor ranking.
 * Checks exact intent IDs/names, exact registered example phrases, and validated learned phrases.
 */
public class ExactMatcher {

    private final IntentCatalog catalog;
    private final Map<String, String> examplePhraseIndex = new ConcurrentHashMap<>();
    private final Map<String, String> learnedPhraseIndex = new ConcurrentHashMap<>();

    public ExactMatcher(IntentCatalog catalog) {
        this.catalog = catalog != null ? catalog : IntentCatalog.defaultCatalog();
        buildExampleIndex();
    }

    public ExactMatcher() {
        this(IntentCatalog.defaultCatalog());
    }

    private void buildExampleIndex() {
        examplePhraseIndex.clear();
        for (IntentDefinition def : catalog.getAllIntents()) {
            for (String example : def.getExamplePhrases()) {
                String normalized = QueryPreprocessor.preprocess(example).getNormalizedQuery();
                if (!normalized.isEmpty()) {
                    examplePhraseIndex.put(normalized, def.getIntentName());
                }
            }
        }
    }

    /**
     * Registers a validated learned phrase mapping.
     */
    public void addLearnedPhrase(String rawQuery, String intentName) {
        if (rawQuery != null && intentName != null) {
            String normalized = QueryPreprocessor.preprocess(rawQuery).getNormalizedQuery();
            if (!normalized.isEmpty()) {
                learnedPhraseIndex.put(normalized, intentName.trim().toUpperCase());
            }
        }
    }

    public boolean hasLearnedPhrase(String rawQuery) {
        if (rawQuery == null) return false;
        String normalized = QueryPreprocessor.preprocess(rawQuery).getNormalizedQuery();
        return learnedPhraseIndex.containsKey(normalized);
    }

    public void clearLearnedPhrases() {
        learnedPhraseIndex.clear();
    }

    /**
     * Attempts fast exact match on intentName, registered example, or learned phrase.
     *
     * @return IntentMatchResult if exact match is confident, or null if no exact match.
     */
    public IntentMatchResult match(QueryFeatures features) {
        if (features == null) return null;

        String normalized = features.getNormalizedQuery();
        if (normalized.isEmpty()) return null;

        // 1. Check exact intent name match (e.g. "ENABLE_BLUETOOTH" or "enable_bluetooth")
        String candidateId = normalized.replace(" ", "_").toUpperCase();
        IntentDefinition defById = catalog.findById(candidateId);
        if (defById != null) {
            UserIntent intent = new UserIntent(
                    defById.getIntentName(),
                    features.getRawQuery(),
                    defById.getCategory(),
                    Collections.emptyMap()
            );
            return IntentMatchResult.exactMatch(intent);
        }

        // 2. Check exact registered example phrase match
        String intentFromExample = examplePhraseIndex.get(normalized);
        if (intentFromExample != null) {
            IntentDefinition def = catalog.findById(intentFromExample);
            if (def != null) {
                UserIntent intent = new UserIntent(
                        def.getIntentName(),
                        features.getRawQuery(),
                        def.getCategory(),
                        Collections.emptyMap()
                );
                return IntentMatchResult.exactMatch(intent);
            }
        }

        // 3. Check exact validated learned phrase match
        String intentFromLearned = learnedPhraseIndex.get(normalized);
        if (intentFromLearned != null) {
            IntentDefinition def = catalog.findById(intentFromLearned);
            String category = def != null ? def.getCategory() : "General";
            UserIntent intent = new UserIntent(
                    intentFromLearned,
                    features.getRawQuery(),
                    category,
                    Collections.emptyMap()
            );
            return IntentMatchResult.exactMatch(intent);
        }

        return null;
    }
}
