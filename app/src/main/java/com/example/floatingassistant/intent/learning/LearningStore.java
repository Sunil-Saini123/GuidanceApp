package com.example.floatingassistant.intent.learning;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LearningStore — Manages the lifecycle of learned query-to-intent mappings.
 * Enforces validation states (NEW -> PENDING_VALIDATION -> VALIDATED / REJECTED)
 * to prevent unvalidated AI answers from contaminating local matching indexes.
 */
public class LearningStore {

    public enum ValidationStatus {
        NEW,
        PENDING_VALIDATION,
        VALIDATED,
        REJECTED
    }

    public static class LearningRecord {
        private final String rawQuery;
        private final String intentName;
        private final String category;
        private final Map<String, String> parameters;
        private ValidationStatus status;
        private final long timestamp;

        public LearningRecord(String rawQuery, String intentName, String category, Map<String, String> parameters, ValidationStatus status) {
            this.rawQuery = rawQuery != null ? rawQuery.trim() : "";
            this.intentName = intentName != null ? intentName.trim().toUpperCase(Locale.US) : "";
            this.category = category != null ? category.trim() : "General";
            this.parameters = parameters != null ? new HashMap<>(parameters) : new HashMap<>();
            this.status = status != null ? status : ValidationStatus.NEW;
            this.timestamp = System.currentTimeMillis();
        }

        public String getRawQuery() { return rawQuery; }
        public String getIntentName() { return intentName; }
        public String getCategory() { return category; }
        public Map<String, String> getParameters() { return Collections.unmodifiableMap(parameters); }
        public ValidationStatus getStatus() { return status; }
        public void setStatus(ValidationStatus status) { this.status = status; }
        public long getTimestamp() { return timestamp; }
    }

    private final Map<String, LearningRecord> records = new ConcurrentHashMap<>();

    public synchronized LearningRecord recordExample(String rawQuery, String intentName, String category, Map<String, String> params, boolean autoValidate) {
        if (rawQuery == null || intentName == null || rawQuery.trim().isEmpty()) {
            return null;
        }

        ValidationStatus initialStatus = autoValidate ? ValidationStatus.VALIDATED : ValidationStatus.NEW;
        LearningRecord record = new LearningRecord(rawQuery, intentName, category, params, initialStatus);
        records.put(rawQuery.trim().toLowerCase(Locale.US), record);
        return record;
    }

    public synchronized void validate(String rawQuery) {
        if (rawQuery == null) return;
        LearningRecord record = records.get(rawQuery.trim().toLowerCase(Locale.US));
        if (record != null) {
            record.setStatus(ValidationStatus.VALIDATED);
        }
    }

    public synchronized void reject(String rawQuery) {
        if (rawQuery == null) return;
        LearningRecord record = records.get(rawQuery.trim().toLowerCase(Locale.US));
        if (record != null) {
            record.setStatus(ValidationStatus.REJECTED);
        }
    }

    public synchronized List<LearningRecord> getValidatedRecords() {
        List<LearningRecord> validated = new ArrayList<>();
        for (LearningRecord rec : records.values()) {
            if (rec.getStatus() == ValidationStatus.VALIDATED) {
                validated.add(rec);
            }
        }
        return validated;
    }

    public synchronized List<LearningRecord> getAllRecords() {
        return new ArrayList<>(records.values());
    }

    public synchronized void clear() {
        records.clear();
    }
}
