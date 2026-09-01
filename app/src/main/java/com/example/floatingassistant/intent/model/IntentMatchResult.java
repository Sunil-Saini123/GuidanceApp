package com.example.floatingassistant.intent.model;

import com.example.floatingassistant.pathgenerator.UserIntent;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * IntentMatchResult — The final result produced by the Intent Classification Engine.
 * Wraps the standard UserIntent contract alongside match metadata, confidence scores,
 * and rejection explanations.
 */
public class IntentMatchResult {

    public enum MatchSource {
        LOCAL_EXACT,
        LOCAL_HYBRID,
        GROQ_FALLBACK,
        UNKNOWN
    }

    private final UserIntent userIntent;
    private final double confidence;
    private final MatchSource matchSource;
    private final Map<String, Double> candidateScores;
    private final boolean wasRejected;
    private final String rejectionReason;

    public IntentMatchResult(UserIntent userIntent,
                             double confidence,
                             MatchSource matchSource,
                             Map<String, Double> candidateScores,
                             boolean wasRejected,
                             String rejectionReason) {
        this.userIntent = userIntent;
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
        this.matchSource = matchSource != null ? matchSource : MatchSource.UNKNOWN;
        this.candidateScores = candidateScores != null ? new HashMap<>(candidateScores) : new HashMap<>();
        this.wasRejected = wasRejected;
        this.rejectionReason = rejectionReason != null ? rejectionReason : "";
    }

    public static IntentMatchResult exactMatch(UserIntent userIntent) {
        return new IntentMatchResult(userIntent, 1.0, MatchSource.LOCAL_EXACT, Collections.emptyMap(), false, "");
    }

    public static IntentMatchResult hybridMatch(UserIntent userIntent, double confidence, Map<String, Double> scores) {
        return new IntentMatchResult(userIntent, confidence, MatchSource.LOCAL_HYBRID, scores, false, "");
    }

    public static IntentMatchResult groqMatch(UserIntent userIntent, double confidence) {
        return new IntentMatchResult(userIntent, confidence, MatchSource.GROQ_FALLBACK, Collections.emptyMap(), false, "");
    }

    public static IntentMatchResult reject(String rawQuery, String reason, Map<String, Double> scores) {
        UserIntent unknown = new UserIntent("UNKNOWN", rawQuery, "General", Collections.emptyMap());
        return new IntentMatchResult(unknown, 0.0, MatchSource.UNKNOWN, scores, true, reason);
    }

    public UserIntent getUserIntent() {
        return userIntent;
    }

    public double getConfidence() {
        return confidence;
    }

    public MatchSource getMatchSource() {
        return matchSource;
    }

    public Map<String, Double> getCandidateScores() {
        return Collections.unmodifiableMap(candidateScores);
    }

    public boolean isWasRejected() {
        return wasRejected;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public boolean isConfident() {
        return !wasRejected && userIntent != null && userIntent.isValid() && matchSource != MatchSource.UNKNOWN;
    }

    @Override
    public String toString() {
        return "IntentMatchResult{" +
                "userIntent=" + userIntent +
                ", confidence=" + confidence +
                ", matchSource=" + matchSource +
                ", wasRejected=" + wasRejected +
                ", rejectionReason='" + rejectionReason + '\'' +
                ", candidateScores=" + candidateScores +
                '}';
    }
}
