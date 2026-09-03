package com.example.floatingassistant.intent.evaluation;

/**
 * AccuracyMetrics — Quantifies accuracy, rejection safety, and error rates of classification runs.
 */
public class AccuracyMetrics {

    private final int totalQueries;
    private final int correctPredictions;
    private final int falsePositives; // Confidently predicted the WRONG intent
    private final int correctRejections; // Correctly rejected out-of-domain/ambiguous queries
    private final int uncertainSentToFallback; // Unconfident predictions routed to Groq

    public AccuracyMetrics(int totalQueries,
                           int correctPredictions,
                           int falsePositives,
                           int correctRejections,
                           int uncertainSentToFallback) {
        this.totalQueries = totalQueries;
        this.correctPredictions = correctPredictions;
        this.falsePositives = falsePositives;
        this.correctRejections = correctRejections;
        this.uncertainSentToFallback = uncertainSentToFallback;
    }

    public int getTotalQueries() { return totalQueries; }
    public int getCorrectPredictions() { return correctPredictions; }
    public int getFalsePositives() { return falsePositives; }
    public int getCorrectRejections() { return correctRejections; }
    public int getUncertainSentToFallback() { return uncertainSentToFallback; }

    public double getOverallAccuracy() {
        return totalQueries > 0 ? ((double) (correctPredictions + correctRejections) / totalQueries) : 0.0;
    }

    public double getFalsePositiveRate() {
        return totalQueries > 0 ? ((double) falsePositives / totalQueries) : 0.0;
    }

    public double getLocalResolutionRate() {
        return totalQueries > 0 ? ((double) (totalQueries - uncertainSentToFallback) / totalQueries) : 0.0;
    }

    public double getFallbackRate() {
        return totalQueries > 0 ? ((double) uncertainSentToFallback / totalQueries) : 0.0;
    }

    @Override
    public String toString() {
        return String.format(
                "AccuracyMetrics: Total=%d | Correct=%d (%.1f%%) | FalsePositives=%d (%.1f%%) | Rejections=%d | FallbackRate=%.1f%%",
                totalQueries,
                correctPredictions + correctRejections,
                getOverallAccuracy() * 100.0,
                falsePositives,
                getFalsePositiveRate() * 100.0,
                correctRejections,
                getFallbackRate() * 100.0
        );
    }
}
