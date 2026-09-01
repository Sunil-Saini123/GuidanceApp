package com.example.floatingassistant.intent.model;

/**
 * ExtractedParameter — Encapsulates a named parameter extracted from the user's query.
 */
public class ExtractedParameter {

    private final String name;
    private final String value;
    private final IntentDefinition.ParameterType type;
    private final double confidence;

    public ExtractedParameter(String name, String value, IntentDefinition.ParameterType type, double confidence) {
        this.name = name != null ? name.trim() : "";
        this.value = value != null ? value.trim() : "";
        this.type = type != null ? type : IntentDefinition.ParameterType.STRING;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public ExtractedParameter(String name, String value) {
        this(name, value, IntentDefinition.ParameterType.STRING, 1.0);
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public IntentDefinition.ParameterType getType() {
        return type;
    }

    public double getConfidence() {
        return confidence;
    }

    public boolean isValid() {
        return !name.isEmpty() && !value.isEmpty();
    }

    @Override
    public String toString() {
        return "ExtractedParameter{" +
                "name='" + name + '\'' +
                ", value='" + value + '\'' +
                ", type=" + type +
                ", confidence=" + confidence +
                '}';
    }
}
