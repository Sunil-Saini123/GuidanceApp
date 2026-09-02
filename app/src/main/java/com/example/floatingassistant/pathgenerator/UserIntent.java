package com.example.floatingassistant.pathgenerator;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
// Understanding 
/* this is a fucntion which is being called with the intentName, rawQuery
 and targetCategory and parameters and it will just store those data. and gives us
 various fucntion which can be used to get and verify the data and ask about data .
 */
/**
 * UserIntent — Represents an intent received from the Intent Classifier.
 *
 * Designed as an explicit contract so the teammate developing the Intent Classifier
 * can pass structured intent data into the Path Generation module.
 */
public class UserIntent {

    private final String intentName;
    private final String rawQuery;
    private final String targetCategory;
    private final Map<String, String> parameters;

    public UserIntent(String intentName, String rawQuery, String targetCategory, Map<String, String> parameters) {
        this.intentName = intentName != null ? intentName.trim() : "";
        this.rawQuery = rawQuery != null ? rawQuery.trim() : "";
        this.targetCategory = targetCategory != null ? targetCategory.trim() : "Settings";
        this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
    }

    public UserIntent(String intentName, String rawQuery) {
        this(intentName, rawQuery, "Settings", Collections.emptyMap());
    }

    public String getIntentName() {
        return intentName;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public String getTargetCategory() {
        return targetCategory;
    }

    public Map<String, String> getParameters() {
        return Collections.unmodifiableMap(parameters);
    }

    public boolean isValid() {
        return !intentName.isEmpty() || !rawQuery.isEmpty();
    }

    @Override
    public String toString() {
        return "UserIntent{" +
                "intentName='" + intentName + '\'' +
                ", rawQuery='" + rawQuery + '\'' +
                ", targetCategory='" + targetCategory + '\'' +
                ", parameters=" + parameters +
                '}';
    }
}
