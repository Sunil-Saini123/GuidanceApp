package com.example.floatingassistant.intent.fallback;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.IntentMatchResult;
import com.example.floatingassistant.intent.model.QueryFeatures;
import com.example.floatingassistant.pathgenerator.AppLogger;
import com.example.floatingassistant.pathgenerator.GroqProxyClient;
import com.example.floatingassistant.pathgenerator.PathRequest;
import com.example.floatingassistant.pathgenerator.UserIntent;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GroqFallbackHandler — Routes uncertain/ambiguous user queries to the Groq Proxy API
 * requesting strict structured JSON intent classification.
 */
public class GroqFallbackHandler {

    private static final String TAG = "GroqFallbackHandler";

    public static final String SYSTEM_PROMPT =
            "You are an Intent Classifier expert for an Android Guidance Assistant.\n" +
            "Your task is to classify the user's natural language request into a specific Android intent.\n\n" +
            "CRITICAL INSTRUCTIONS:\n" +
            "1. Output MUST be strict JSON only. Do NOT include markdown code blocks (```json) or extra text.\n" +
            "2. Required JSON format:\n" +
            "{\n" +
            "  \"intentName\": \"<CANONICAL_INTENT_NAME>\",\n" +
            "  \"category\": \"<Category>\",\n" +
            "  \"parameters\": {\n" +
            "    \"<param_name>\": \"<param_value>\"\n" +
            "  },\n" +
            "  \"confidence\": 0.95\n" +
            "}\n" +
            "3. If the query is completely outside device navigation or unclear, return intentName: \"UNKNOWN\" and confidence: 0.0.";

    private final GroqProxyClient groqClient;
    private final IntentCatalog catalog;

    public GroqFallbackHandler(GroqProxyClient groqClient, IntentCatalog catalog) {
        this.groqClient = groqClient != null ? groqClient : new GroqProxyClient();
        this.catalog = catalog != null ? catalog : IntentCatalog.defaultCatalog();
    }

    public GroqFallbackHandler() {
        this(new GroqProxyClient(), IntentCatalog.defaultCatalog());
    }

    public GroqProxyClient getGroqClient() {
        return groqClient;
    }

    /**
     * Resolves an uncertain query by calling Groq Proxy API.
     */
    public IntentMatchResult resolveFallback(QueryFeatures features, Map<String, Double> localCandidateScores) {
        if (features == null || features.getRawQuery().isEmpty()) {
            return IntentMatchResult.reject("", "Empty query for fallback", null);
        }

        AppLogger.i(TAG, "🌐 GROQ FALLBACK TRIGGERED for query: \"" + features.getRawQuery() + "\"");

        try {
            // Build structured request prompt for Groq
            String userPrompt = buildPrompt(features, localCandidateScores);

            // Construct PathRequest wrapper for GroqProxyClient
            UserIntent fallbackPromptIntent = new UserIntent("CLASSIFY_INTENT", userPrompt, "IntentClassification", CollectionsMap(features));
            PathRequest pathReq = new PathRequest(fallbackPromptIntent, "ClassificationEngine");

            String rawResponse = groqClient.sendRequest(pathReq);
            AppLogger.d(TAG, "📥 Groq Raw Intent Classification Output: " + rawResponse);

            return parseGroqResponse(features.getRawQuery(), rawResponse);

        } catch (Exception e) {
            AppLogger.e(TAG, "❌ Groq Fallback Request Failed: " + e.getMessage(), e);
            return IntentMatchResult.reject(features.getRawQuery(), "Groq fallback communication failed: " + e.getMessage(), localCandidateScores);
        }
    }

    private String buildPrompt(QueryFeatures features, Map<String, Double> localScores) {
        StringBuilder sb = new StringBuilder();
        sb.append("User Query: \"").append(features.getRawQuery()).append("\"\n\n");

        sb.append("Available Catalog Intents:\n");
        for (IntentDefinition def : catalog.getAllIntents()) {
            sb.append("- ").append(def.getIntentName()).append(" (").append(def.getCategory()).append("): ")
                    .append(def.getDescription()).append("\n");
        }

        if (localScores != null && !localScores.isEmpty()) {
            sb.append("\nLocal Ambiguous Candidates:\n");
            for (Map.Entry<String, Double> entry : localScores.entrySet()) {
                sb.append("- ").append(entry.getKey()).append(": score=").append(String.format("%.2f", entry.getValue())).append("\n");
            }
        }

        sb.append("\nReturn the single best matched intent in strict JSON format.");
        return sb.toString();
    }

    public static IntentMatchResult parseGroqResponse(String rawQuery, String rawResponse) {
        if (rawResponse == null || rawResponse.trim().isEmpty()) {
            return IntentMatchResult.reject(rawQuery, "Empty response from Groq", null);
        }

        String cleaned = sanitizeJson(rawResponse);

        try {
            JSONObject json = new JSONObject(cleaned);
            String intentName = json.optString("intentName", "UNKNOWN").trim().toUpperCase();
            String category = json.optString("category", "General").trim();
            double confidence = json.optDouble("confidence", 0.90);

            Map<String, String> parameters = new HashMap<>();
            if (json.has("parameters")) {
                JSONObject paramObj = json.getJSONObject("parameters");
                for (String key : (Iterable<String>) paramObj::keys) {
                    parameters.put(key, paramObj.getString(key));
                }
            }

            if ("UNKNOWN".equalsIgnoreCase(intentName) || confidence < 0.50) {
                return IntentMatchResult.reject(rawQuery, "Groq returned UNKNOWN or low confidence", null);
            }

            UserIntent userIntent = new UserIntent(intentName, rawQuery, category, parameters);
            AppLogger.i(TAG, "✨ GROQ FALLBACK RESOLVED -> Intent: " + intentName + " (" + category + ")");
            return IntentMatchResult.groqMatch(userIntent, confidence);

        } catch (Exception e) {
            AppLogger.e(TAG, "❌ Failed to parse Groq structured JSON: " + e.getMessage());
            return IntentMatchResult.reject(rawQuery, "Malformed Groq response: " + e.getMessage(), null);
        }
    }

    private static String sanitizeJson(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        return trimmed.trim();
    }

    private Map<String, String> CollectionsMap(QueryFeatures features) {
        return features != null ? features.getParametersAsMap() : new HashMap<>();
    }
}
