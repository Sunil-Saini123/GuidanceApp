package com.example.floatingassistant.pathgenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GroqResponseParser — Parses the raw Groq response and converts
 * it into a NavigationPath object for the path generation module.
 */
public class GroqResponseParser {

    private static final String TAG = "GroqResponseParser";

    // [PERMANENT FIX] Do not remove this! Handle conversational preamble, markdown code blocks, and direct JSON arrays from LLM responses.
    private static final Pattern MARKDOWN_BLOCK_PATTERN =
            Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);

    /**
     * Parses the raw output from Groq into a structured NavigationPath.
     */
    public static NavigationPath parse(String rawGroqOutput) {
        if (rawGroqOutput == null || rawGroqOutput.trim().isEmpty()) {
            AppLogger.w(TAG, "⚠️ Parse Failed: Raw response is null or empty");
            return NavigationPath.failure("Empty or null response from Groq");
        }

        String cleanedJson = sanitizeJson(rawGroqOutput);
        AppLogger.d(TAG, "🔍 Sanitized JSON for Parsing: " + cleanedJson);

        try {
            List<String> steps = new ArrayList<>();
            String destination = "";

            if (cleanedJson.startsWith("[")) {
                // Direct JSON array of steps: ["Settings", "Sound & vibration", "Media volume"]
                JSONArray arr = new JSONArray(cleanedJson);
                for (int i = 0; i < arr.length(); i++) {
                    String step = cleanStep(arr.getString(i));
                    if (!step.isEmpty()) {
                        steps.add(step);
                    }
                }
                if (!steps.isEmpty()) {
                    destination = steps.get(steps.size() - 1);
                }
            } else {
                // Expected JSON object: {"destination": "...", "path": [...]}
                JSONObject json = new JSONObject(cleanedJson);
                destination = json.optString("destination", "").trim();

                // Check "path", "steps", "navigation_path", or "actions"
                JSONArray arr = null;
                if (json.has("path")) {
                    arr = json.optJSONArray("path");
                } else if (json.has("steps")) {
                    arr = json.optJSONArray("steps");
                } else if (json.has("navigation_path")) {
                    arr = json.optJSONArray("navigation_path");
                } else if (json.has("actions")) {
                    arr = json.optJSONArray("actions");
                }

                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        String step = cleanStep(arr.getString(i));
                        if (!step.isEmpty()) {
                            steps.add(step);
                        }
                    }
                }

                if (destination.isEmpty() && !steps.isEmpty()) {
                    destination = steps.get(steps.size() - 1);
                }
            }

            if (steps.isEmpty()) {
                AppLogger.w(TAG, "❌ Parse Failed: 'path' array is empty in JSON");
                return NavigationPath.failure("Path steps array is empty");
            }

            AppLogger.i(TAG, "✅ PARSE SUCCESS: Destination=\"" + destination + "\", Steps=" + steps);
            return NavigationPath.success(destination, steps, rawGroqOutput);

        } catch (Exception e) {
            AppLogger.e(TAG, "❌ PARSE ERROR: Failed to parse JSON from Groq output: " + e.getMessage());
            AppLogger.d(TAG, "Raw text was: " + rawGroqOutput);
            return NavigationPath.failure("Malformed JSON response: " + e.getMessage());
        }
    }

    /**
     * Cleans step text by stripping markdown bullet points or numbering (e.g. "1. Sound" -> "Sound").
     */
    private static String cleanStep(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        s = s.replaceAll("^[0-9]+[.)]\\s*", "");
        s = s.replaceAll("^[-*•]\\s*", "");
        if ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'"))) {
            if (s.length() >= 2) {
                s = s.substring(1, s.length() - 1).trim();
            }
        }
        return s;
    }

    /**
     * Robustly extracts JSON payload from raw LLM output, extracting from markdown code blocks
     * and stripping any conversational preambles/postscripts.
     */
    private static String sanitizeJson(String raw) {
        if (raw == null) return "";
        String text = raw.trim();

        // 1. Check for markdown code fences (```json ... ``` or ``` ... ```)
        Matcher matcher = MARKDOWN_BLOCK_PATTERN.matcher(text);
        if (matcher.find()) {
            String block = matcher.group(1).trim();
            String inner = extractBracketsOrBraces(block);
            if (!inner.isEmpty()) {
                return inner;
            }
            if (!block.isEmpty()) {
                return block;
            }
        }

        // 2. Locate outermost JSON object or array bounds in raw text
        String inner = extractBracketsOrBraces(text);
        return !inner.isEmpty() ? inner : text;
    }

    private static String extractBracketsOrBraces(String text) {
        int firstBrace = text.indexOf('{');
        int lastBrace = text.lastIndexOf('}');
        int firstBracket = text.indexOf('[');
        int lastBracket = text.lastIndexOf(']');

        boolean hasObject = firstBrace != -1 && lastBrace > firstBrace;
        boolean hasArray = firstBracket != -1 && lastBracket > firstBracket;

        if (hasObject && hasArray) {
            if (firstBrace < firstBracket) {
                return text.substring(firstBrace, lastBrace + 1).trim();
            } else {
                return text.substring(firstBracket, lastBracket + 1).trim();
            }
        } else if (hasObject) {
            return text.substring(firstBrace, lastBrace + 1).trim();
        } else if (hasArray) {
            return text.substring(firstBracket, lastBracket + 1).trim();
        }
        return "";
    }
}
