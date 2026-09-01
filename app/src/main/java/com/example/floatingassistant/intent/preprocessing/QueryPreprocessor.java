package com.example.floatingassistant.intent.preprocessing;

import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * QueryPreprocessor — Converts messy natural language into a clean, normalized representation
 * while strictly preserving the raw query string for parameters and debugging.
 */
public class QueryPreprocessor {

    private static final Pattern MULTIPLE_SPACES = Pattern.compile("\\s+");
    private static final Pattern PUNCTUATION_CLEANER = Pattern.compile("[^a-zA-Z0-9\\s'%\\-]");
    private static final Pattern WIFI_VARIANTS = Pattern.compile("\\b(wi\\s*-\\s*fi|wi\\s+fi)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Preprocesses a raw user query string into a structured QueryFeatures object.
     */
    public static QueryFeatures preprocess(String rawQuery) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return new QueryFeatures(rawQuery != null ? rawQuery : "", "", Collections.emptyList());
        }

        String trimmed = rawQuery.trim();

        // 1. Lowercase
        String lower = trimmed.toLowerCase(Locale.US);

        // 2. Normalize Wi-Fi variants ("wi-fi", "wi fi" -> "wifi")
        String wifiNormalized = WIFI_VARIANTS.matcher(lower).replaceAll("wifi");

        // 3. Normalize punctuation (replace punctuation with spaces except internal hyphens/apostrophes)
        String punctuationCleaned = PUNCTUATION_CLEANER.matcher(wifiNormalized).replaceAll(" ");

        // 4. Normalize multiple consecutive spaces
        String spaceNormalized = MULTIPLE_SPACES.matcher(punctuationCleaned).replaceAll(" ").trim();

        // 5. Tokenize into words
        List<String> tokens = new ArrayList<>();
        if (!spaceNormalized.isEmpty()) {
            String[] rawTokens = spaceNormalized.split(" ");
            for (String t : rawTokens) {
                String cleanToken = t.trim();
                if (!cleanToken.isEmpty()) {
                    tokens.add(cleanToken);
                }
            }
        }

        return new QueryFeatures(rawQuery, spaceNormalized, tokens);
    }
}
