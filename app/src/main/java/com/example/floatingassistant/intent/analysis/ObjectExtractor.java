package com.example.floatingassistant.intent.analysis;

import com.example.floatingassistant.intent.catalog.IntentCatalog;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ObjectExtractor — Identifies the primary object/domain of the query
 * (e.g., wifi, bluetooth, display, battery, sound, accessibility).
 */
public class ObjectExtractor {

    private final Map<String, String> objectVocabulary = new HashMap<>();

    public ObjectExtractor(IntentCatalog catalog) {
        buildObjectVocabulary(catalog);
    }

    public ObjectExtractor() {
        this(IntentCatalog.defaultCatalog());
    }

    private void buildObjectVocabulary(IntentCatalog catalog) {
        objectVocabulary.clear();

        // Built-in core domain synonyms
        registerDomain("wifi", "wifi", "wi-fi", "wi fi", "wireless network", "wireless", "wlan", "hotspot");
        registerDomain("bluetooth", "bluetooth", "bt");
        registerDomain("display", "display", "screen", "brightness", "screen brightness", "display settings");
        registerDomain("security", "security", "privacy", "lock screen", "permission", "permissions");
        registerDomain("wallpaper", "wallpaper", "background", "theme");
        registerDomain("battery", "battery", "battery saver", "power saving", "power");
        registerDomain("sound", "sound", "volume", "vibration", "ringtone", "audio");
        registerDomain("accessibility", "accessibility", "screen reader", "talkback");

        // Index all aliases from catalog
        if (catalog != null) {
            for (IntentDefinition def : catalog.getAllIntents()) {
                String domain = def.getCategory().toLowerCase(Locale.US);
                for (String alias : def.getObjectAliases()) {
                    objectVocabulary.put(alias.toLowerCase(Locale.US), domain);
                }
            }
        }
    }

    private void registerDomain(String canonical, String... aliases) {
        for (String alias : aliases) {
            objectVocabulary.put(alias.toLowerCase(Locale.US), canonical);
        }
    }

    /**
     * Extracts canonical object/domain from query features.
     */
    public String extractObject(QueryFeatures features) {
        if (features == null) return "";

        String normalized = features.getNormalizedQuery();

        // 1. Check longest multi-word object phrases first
        String bestMatch = "";
        int maxLen = 0;

        for (Map.Entry<String, String> entry : objectVocabulary.entrySet()) {
            String phrase = entry.getKey();
            if (normalized.contains(phrase) && phrase.length() > maxLen) {
                bestMatch = entry.getValue();
                maxLen = phrase.length();
            }
        }

        if (!bestMatch.isEmpty()) {
            return bestMatch;
        }

        // 2. Check single tokens
        List<String> tokens = features.getTokens();
        for (String token : tokens) {
            String domain = objectVocabulary.get(token);
            if (domain != null) {
                return domain;
            }
        }

        return "";
    }
}
