package com.example.floatingassistant.intent.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * QueryFeatures — Structured representation of a preprocessed user query with its tokens,
 * detected actions/objects, and extracted parameter values.
 */
public class QueryFeatures {

    private final String rawQuery;
    private final String normalizedQuery;
    private final List<String> tokens;
    private final String detectedAction;
    private final String detectedObject;
    private final List<ExtractedParameter> extractedParameters;

    public QueryFeatures(String rawQuery,
                         String normalizedQuery,
                         List<String> tokens,
                         String detectedAction,
                         String detectedObject,
                         List<ExtractedParameter> extractedParameters) {
        this.rawQuery = rawQuery != null ? rawQuery : "";
        this.normalizedQuery = normalizedQuery != null ? normalizedQuery.trim() : "";
        this.tokens = tokens != null ? new ArrayList<>(tokens) : new ArrayList<>();
        this.detectedAction = detectedAction != null ? detectedAction.trim().toLowerCase() : "";
        this.detectedObject = detectedObject != null ? detectedObject.trim().toLowerCase() : "";
        this.extractedParameters = extractedParameters != null ? new ArrayList<>(extractedParameters) : new ArrayList<>();
    }

    public QueryFeatures(String rawQuery, String normalizedQuery, List<String> tokens) {
        this(rawQuery, normalizedQuery, tokens, "", "", Collections.emptyList());
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public List<String> getTokens() {
        return Collections.unmodifiableList(tokens);
    }

    public String getDetectedAction() {
        return detectedAction;
    }

    public String getDetectedObject() {
        return detectedObject;
    }

    public List<ExtractedParameter> getExtractedParameters() {
        return Collections.unmodifiableList(extractedParameters);
    }

    public Map<String, String> getParametersAsMap() {
        Map<String, String> map = new HashMap<>();
        for (ExtractedParameter param : extractedParameters) {
            if (param.isValid()) {
                map.put(param.getName(), param.getValue());
            }
        }
        return map;
    }

    public String getParameterValue(String name) {
        if (name == null) return null;
        for (ExtractedParameter param : extractedParameters) {
            if (name.equalsIgnoreCase(param.getName())) {
                return param.getValue();
            }
        }
        return null;
    }

    public boolean hasParameter(String name) {
        return getParameterValue(name) != null;
    }

    public QueryFeatures copyWithAction(String action) {
        return new QueryFeatures(rawQuery, normalizedQuery, tokens, action, detectedObject, extractedParameters);
    }

    public QueryFeatures copyWithObject(String object) {
        return new QueryFeatures(rawQuery, normalizedQuery, tokens, detectedAction, object, extractedParameters);
    }

    public QueryFeatures copyWithParameters(List<ExtractedParameter> params) {
        return new QueryFeatures(rawQuery, normalizedQuery, tokens, detectedAction, detectedObject, params);
    }

    @Override
    public String toString() {
        return "QueryFeatures{" +
                "rawQuery='" + rawQuery + '\'' +
                ", normalizedQuery='" + normalizedQuery + '\'' +
                ", tokens=" + tokens +
                ", detectedAction='" + detectedAction + '\'' +
                ", detectedObject='" + detectedObject + '\'' +
                ", extractedParameters=" + extractedParameters +
                '}';
    }
}
