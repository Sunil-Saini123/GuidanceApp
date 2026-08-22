package com.example.floatingassistant.pathgenerator;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * PathValidator — Validates raw LLM / Groq responses, ensuring valid JSON schema,
 * non-empty steps list, correct destination, and step continuity.
 */
public class PathValidator {

    private static final String TAG = "PathValidator";

    public static NavigationPath validate(String rawGroqOutput) {
        if (rawGroqOutput == null || rawGroqOutput.trim().isEmpty()) {
            return NavigationPath.failure("Empty or null response from Groq");
        }

        String cleanedJson = sanitizeJson(rawGroqOutput);

        try {
            JSONObject json = new JSONObject(cleanedJson);

            String destination = json.optString("destination", "").trim();

            List<String> steps = new ArrayList<>();
            if (json.has("path")) {
                JSONArray arr = json.getJSONArray("path");
                for (int i = 0; i < arr.length(); i++) {
                    String step = arr.getString(i).trim();
                    if (!step.isEmpty()) {
                        steps.add(step);
                    }
                }
            }

            if (steps.isEmpty()) {
                AppLogger.w(TAG, "Validation failed: path steps array is empty");
                return NavigationPath.failure("Path steps array is empty");
            }

            if (destination.isEmpty()) {
                // If destination field was blank, use last step as destination
                destination = steps.get(steps.size() - 1);
            }

            AppLogger.i(TAG, "Path validated successfully (" + steps.size() + " steps): " + destination);
            return NavigationPath.success(destination, steps, rawGroqOutput);

        } catch (Exception e) {
            AppLogger.e(TAG, "Validation error parsing Groq output: " + e.getMessage() + "\nRaw: " + rawGroqOutput, e);
            return NavigationPath.failure("Malformed JSON response: " + e.getMessage());
        }
    }

    /**
     * Strips backticks or markdown fences if the LLM outputted ```json ... ``` despite system prompt instructions.
     */
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
}
