package com.example.floatingassistant.intent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * IntentDefinition — Represents a generic intent registered in the Intent Catalog.
 * The matching engine consumes these definitions dynamically without hardcoding intent logic.
 */
public class IntentDefinition {

    public enum ParameterType {
        STRING,
        NUMBER,
        DURATION,
        BOOLEAN
    }

    public static class ParameterSpec {
        private final ParameterType type;
        private final boolean required;
        private final String description;

        public ParameterSpec(ParameterType type, boolean required, String description) {
            this.type = type != null ? type : ParameterType.STRING;
            this.required = required;
            this.description = description != null ? description : "";
        }

        public ParameterType getType() {
            return type;
        }

        public boolean isRequired() {
            return required;
        }

        public String getDescription() {
            return description;
        }

        @Override
        public String toString() {
            return "ParameterSpec{" +
                    "type=" + type +
                    ", required=" + required +
                    ", description='" + description + '\'' +
                    '}';
        }
    }

    private final String intentName;
    private final String category;
    private final String description;
    private final List<String> actionAliases;
    private final List<String> objectAliases;
    private final List<String> examplePhrases;
    private final Map<String, ParameterSpec> parameters;

    public IntentDefinition(String intentName,
                            String category,
                            String description,
                            List<String> actionAliases,
                            List<String> objectAliases,
                            List<String> examplePhrases,
                            Map<String, ParameterSpec> parameters) {
        this.intentName = intentName != null ? intentName.trim() : "";
        this.category = category != null ? category.trim() : "General";
        this.description = description != null ? description.trim() : "";
        this.actionAliases = actionAliases != null ? new ArrayList<>(actionAliases) : new ArrayList<>();
        this.objectAliases = objectAliases != null ? new ArrayList<>(objectAliases) : new ArrayList<>();
        this.examplePhrases = examplePhrases != null ? new ArrayList<>(examplePhrases) : new ArrayList<>();
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }

    public String getIntentName() {
        return intentName;
    }

    public String getCategory() {
        return category;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getActionAliases() {
        return Collections.unmodifiableList(actionAliases);
    }

    public List<String> getObjectAliases() {
        return Collections.unmodifiableList(objectAliases);
    }

    public List<String> getExamplePhrases() {
        return Collections.unmodifiableList(examplePhrases);
    }

    public synchronized void addExamplePhrase(String phrase) {
        if (phrase != null && !phrase.trim().isEmpty()) {
            String trimmed = phrase.trim();
            if (!examplePhrases.contains(trimmed)) {
                examplePhrases.add(trimmed);
            }
        }
    }

    public Map<String, ParameterSpec> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public boolean hasParameter(String name) {
        return parameters.containsKey(name);
    }

    public boolean hasRequiredParameters() {
        for (ParameterSpec spec : parameters.values()) {
            if (spec.isRequired()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "IntentDefinition{" +
                "intentName='" + intentName + '\'' +
                ", category='" + category + '\'' +
                ", description='" + description + '\'' +
                ", actionAliases=" + actionAliases +
                ", objectAliases=" + objectAliases +
                ", examplePhrases=" + examplePhrases +
                ", parameters=" + parameters +
                '}';
    }
}
