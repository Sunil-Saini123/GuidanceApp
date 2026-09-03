package com.example.floatingassistant.intent;

import com.example.floatingassistant.intent.analysis.ActionExtractor;
import com.example.floatingassistant.intent.analysis.ObjectExtractor;
import com.example.floatingassistant.intent.analysis.ParameterExtractor;
import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.fallback.GroqFallbackHandler;
import com.example.floatingassistant.intent.learning.IntentIndexUpdater;
import com.example.floatingassistant.intent.learning.LearningStore;
import com.example.floatingassistant.intent.model.ExtractedParameter;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.intent.model.ScoringWeights;
import com.example.floatingassistant.intent.preprocessing.QueryPreprocessor;
import com.example.floatingassistant.intent.ranking.ConfidenceEvaluator;
import com.example.floatingassistant.intent.ranking.MultiFactorRanker;
import com.example.floatingassistant.intent.retrieval.CandidateRetriever;
import com.example.floatingassistant.intent.retrieval.ExactMatcher;
import com.example.floatingassistant.pathgenerator.AppLogger;

import java.util.List;
import java.util.Map;

/**
 * IntentClassificationEngine — Main entry point coordinating query preprocessing,
 * exact matching, BM25/semantic candidate retrieval, semantic feature extraction,
 * multi-factor ranking with contradiction penalties, confidence evaluation,
 * Groq fallback resolution, and 3-index adaptive learning.
 */
public class IntentClassificationEngine {

    private static final String TAG = "IntentClassificationEngine";

    private final IntentCatalog catalog;
    private final ExactMatcher exactMatcher;
    private final CandidateRetriever candidateRetriever;
    private final ObjectExtractor objectExtractor;
    private final GroqFallbackHandler fallbackHandler;
    private final LearningStore learningStore;
    private ScoringWeights scoringWeights;
    private boolean enableGroqFallback = false; // Enabled when Groq network resolution is desired

    public IntentClassificationEngine(IntentCatalog catalog,
                                      ScoringWeights weights,
                                      GroqFallbackHandler fallbackHandler,
                                      LearningStore learningStore) {
        this.catalog = catalog != null ? catalog : IntentCatalog.defaultCatalog();
        this.scoringWeights = weights != null ? weights : ScoringWeights.defaultWeights();
        this.exactMatcher = new ExactMatcher(this.catalog);
        this.candidateRetriever = new CandidateRetriever(this.catalog);
        this.objectExtractor = new ObjectExtractor(this.catalog);
        this.fallbackHandler = fallbackHandler != null ? fallbackHandler : new GroqFallbackHandler(null, this.catalog);
        this.learningStore = learningStore != null ? learningStore : new LearningStore();
    }

    public IntentClassificationEngine(IntentCatalog catalog, ScoringWeights weights) {
        this(catalog, weights, null, null);
    }

    public IntentClassificationEngine() {
        this(IntentCatalog.defaultCatalog(), ScoringWeights.defaultWeights(), null, null);
    }

    public IntentCatalog getCatalog() { return catalog; }
    public ExactMatcher getExactMatcher() { return exactMatcher; }
    public CandidateRetriever getCandidateRetriever() { return candidateRetriever; }
    public GroqFallbackHandler getFallbackHandler() { return fallbackHandler; }
    public LearningStore getLearningStore() { return learningStore; }
    public ScoringWeights getScoringWeights() { return scoringWeights; }

    public void setScoringWeights(ScoringWeights scoringWeights) {
        if (scoringWeights != null) {
            this.scoringWeights = scoringWeights;
        }
    }

    public boolean isEnableGroqFallback() { return enableGroqFallback; }
    public void setEnableGroqFallback(boolean enableGroqFallback) {
        this.enableGroqFallback = enableGroqFallback;
    }

    /**
     * Classifies a natural language query into a structured IntentMatchResult.
     */
    public IntentMatchResult classify(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return IntentMatchResult.reject("", "Empty query", null);
        }

        AppLogger.i(TAG, "🔍 CLASSIFYING QUERY: \"" + rawQuery + "\"");

        // 1. Preprocessing
        QueryFeatures baseFeatures = QueryPreprocessor.preprocess(rawQuery);

        // 2. Exact Fast Matching
        IntentMatchResult exactResult = exactMatcher.match(baseFeatures);
        if (exactResult != null && exactResult.isConfident()) {
            AppLogger.i(TAG, "⚡ EXACT MATCH HIT: " + exactResult.getUserIntent().getIntentName());
            return exactResult;
        }

        // 3. Feature Extraction (Action, Object/Domain, Parameters)
        String action = ActionExtractor.extractAction(baseFeatures);
        String object = objectExtractor.extractObject(baseFeatures);
        List<ExtractedParameter> params = ParameterExtractor.extractParameters(baseFeatures);

        QueryFeatures enrichedFeatures = baseFeatures
                .copyWithAction(action)
                .copyWithObject(object)
                .copyWithParameters(params);

        AppLogger.d(TAG, "📋 Extracted Features: Action=" + action + ", Object=" + object + ", Params=" + params);

        // 4. Candidate Retrieval (BM25 + Semantic) -> Top-K
        List<CandidateRetriever.ScoredCandidate> candidates =
                candidateRetriever.retrieveCandidates(enrichedFeatures, scoringWeights);

        if (candidates.isEmpty()) {
            return handleUncertainOrFallback(enrichedFeatures, (Map<String, Double>) null, "No candidate intents retrieved");
        }

        // 5. Multi-Factor Ranking with Contradiction Penalties
        List<MultiFactorRanker.RankedCandidate> ranked =
                MultiFactorRanker.rank(enrichedFeatures, candidates, scoringWeights);

        // 6. Confidence Gate Evaluation (Absolute threshold, Winner margin, Parameter validation)
        IntentMatchResult localResult = ConfidenceEvaluator.evaluate(enrichedFeatures, ranked, scoringWeights);

        if (localResult.isConfident()) {
            AppLogger.i(TAG, "✅ LOCAL HYBRID MATCH: " + localResult.getUserIntent().getIntentName() +
                    " (Confidence=" + String.format("%.2f", localResult.getConfidence()) + ")");
            return localResult;
        }

        // 7. If local matching is uncertain -> trigger Groq Fallback if enabled
        return handleUncertainOrFallback(enrichedFeatures, localResult.getCandidateScores(), localResult.getRejectionReason());
    }

    private IntentMatchResult handleUncertainOrFallback(QueryFeatures features, Map<String, Double> localScores, String reason) {
        if (!enableGroqFallback) {
            AppLogger.w(TAG, "⚠️ LOCAL MATCH UNCERTAIN (Fallback Disabled): " + reason);
            return IntentMatchResult.reject(features.getRawQuery(), reason, localScores);
        }

        AppLogger.i(TAG, "🌐 ROUTING UNCERTAIN QUERY TO GROQ: " + reason);
        IntentMatchResult groqResult = fallbackHandler.resolveFallback(features, localScores);

        // Record for adaptive learning
        if (groqResult.isConfident()) {
            String intentName = groqResult.getUserIntent().getIntentName();
            String category = groqResult.getUserIntent().getTargetCategory();
            Map<String, String> parameters = groqResult.getUserIntent().getParameters();

            LearningStore.LearningRecord record =
                    learningStore.recordExample(features.getRawQuery(), intentName, category, parameters, true);

            // Update all 3 indexes with the validated learning record
            IntentIndexUpdater.applyValidatedExample(this, record);
        }

        return groqResult;
    }
}
