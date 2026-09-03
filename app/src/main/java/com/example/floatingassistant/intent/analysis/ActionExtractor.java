package com.example.floatingassistant.intent.analysis;

import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ActionExtractor — Identifies the primary action verb from query tokens
 * (e.g., connect, enable, disable, open, change, forget).
 */
public class ActionExtractor {

    private static final Map<String, String> ACTION_VOCABULARY = new HashMap<>();

    static {
        // CONNECT
        registerAction("CONNECT", "connect", "join", "link", "pair", "attach");

        // ENABLE
        registerAction("ENABLE", "enable", "turn on", "switch on", "activate", "start");

        // DISABLE
        registerAction("DISABLE", "disable", "turn off", "switch off", "deactivate", "stop");

        // OPEN
        registerAction("OPEN", "open", "go to", "show", "view", "navigate", "see", "launch", "display");

        // CHANGE / SET
        registerAction("CHANGE", "change", "set", "adjust", "update", "modify", "increase", "decrease", "make");

        // FORGET / REMOVE
        registerAction("REMOVE", "forget", "remove", "delete", "clear", "erase", "disconnect");
    }

    private static void registerAction(String canonical, String... aliases) {
        for (String alias : aliases) {
            ACTION_VOCABULARY.put(alias.toLowerCase(Locale.US), canonical);
        }
    }

    /**
     * Extracts canonical action verb from query features.
     */
    public static String extractAction(QueryFeatures features) {
        if (features == null) return "";

        String normalized = features.getNormalizedQuery();

        // 1. Check multi-word actions first (e.g. "turn on", "turn off", "switch on", "go to")
        for (Map.Entry<String, String> entry : ACTION_VOCABULARY.entrySet()) {
            String phrase = entry.getKey();
            if (phrase.contains(" ") && normalized.contains(phrase)) {
                return entry.getValue();
            }
        }

        // 2. Check single token actions
        List<String> tokens = features.getTokens();
        for (String token : tokens) {
            String canonical = ACTION_VOCABULARY.get(token);
            if (canonical != null) {
                return canonical;
            }
        }

        return "";
    }
}
