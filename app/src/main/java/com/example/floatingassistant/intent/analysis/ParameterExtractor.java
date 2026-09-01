package com.example.floatingassistant.intent.analysis;

import com.example.floatingassistant.intent.model.ExtractedParameter;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ParameterExtractor — Generic parameter extractor that parses named entities
 * (network names, numeric values/percentages, duration spans) from raw and normalized query text.
 */
public class ParameterExtractor {

    private static final Pattern PERCENTAGE_PATTERN = Pattern.compile("(\\b\\d{1,3}\\s*%|\\b\\d{1,3}\\s*percent\\b)");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b(\\d{1,4})\\b");
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\b\\d+\\s*(?:seconds?|mins?|minutes?|hours?|hrs?)\\b)");
    private static final Pattern CONNECT_TARGET_PATTERN = Pattern.compile("(?:connect\\s+to|join|pair\\s+with)\\s+([^,!?]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Extracts all applicable parameters from the query features.
     */
    public static List<ExtractedParameter> extractParameters(QueryFeatures features) {
        List<ExtractedParameter> params = new ArrayList<>();
        if (features == null) return params;

        String raw = features.getRawQuery();
        String normalized = features.getNormalizedQuery();

        // 1. Extract network_name target (e.g. "Connect to Rohit's Wi-Fi")
        Matcher connectMatcher = CONNECT_TARGET_PATTERN.matcher(raw);
        if (connectMatcher.find()) {
            String target = connectMatcher.group(1).trim();
            // Clean up trailing qualifiers like "please", "now"
            target = target.replaceAll("(?i)\\b(please|now|network|wifi|wi-fi)\\b", "").trim();
            if (!target.isEmpty()) {
                params.add(new ExtractedParameter("network_name", target, IntentDefinition.ParameterType.STRING, 0.90));
            }
        }

        // 2. Extract percentage value (e.g. "50%")
        Matcher pctMatcher = PERCENTAGE_PATTERN.matcher(normalized);
        if (pctMatcher.find()) {
            String pctVal = pctMatcher.group(1).replaceAll("[^0-9]", "").trim();
            params.add(new ExtractedParameter("value", pctVal, IntentDefinition.ParameterType.NUMBER, 0.95));
            params.add(new ExtractedParameter("unit", "percent", IntentDefinition.ParameterType.STRING, 0.95));
        } else {
            // Check plain number if no percentage
            Matcher numMatcher = NUMBER_PATTERN.matcher(normalized);
            if (numMatcher.find()) {
                String numVal = numMatcher.group(1).trim();
                params.add(new ExtractedParameter("value", numVal, IntentDefinition.ParameterType.NUMBER, 0.70));
            }
        }

        // 3. Extract duration value (e.g. "5 minutes")
        Matcher durMatcher = DURATION_PATTERN.matcher(normalized);
        if (durMatcher.find()) {
            String durVal = durMatcher.group(1).trim();
            params.add(new ExtractedParameter("duration", durVal, IntentDefinition.ParameterType.DURATION, 0.90));
        }

        return params;
    }
}
