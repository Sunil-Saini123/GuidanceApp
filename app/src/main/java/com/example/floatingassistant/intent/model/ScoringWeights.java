package com.example.floatingassistant.intent.model;

/**
 * ScoringWeights — Central configuration class holding all tunable weights,
 * contradiction penalty multipliers, and confidence gate thresholds.
 * Every weight is configurable and can be optimized using the WeightTuner evaluation runner.
 */
public class ScoringWeights {

    // Multi-factor ranking weights
    private double wLexical = 0.25;
    private double wSemantic = 0.20;
    private double wAction = 0.20;
    private double wObject = 0.15;
    private double wParameter = 0.12;
    private double wExample = 0.05;
    private double wCategory = 0.03;
    private double wContradiction = 0.30; // Subtracted penalty multiplier

    // Confidence gate thresholds
    private double minConfidence = 0.60;
    private double minMargin = 0.15;

    // Retrieval configuration
    private int topK = 10;
    private double bm25Weight = 0.60;
    private double semanticWeight = 0.40;

    public ScoringWeights() {
    }

    public ScoringWeights(double wLexical,
                          double wSemantic,
                          double wAction,
                          double wObject,
                          double wParameter,
                          double wExample,
                          double wCategory,
                          double wContradiction,
                          double minConfidence,
                          double minMargin,
                          int topK,
                          double bm25Weight,
                          double semanticWeight) {
        this.wLexical = wLexical;
        this.wSemantic = wSemantic;
        this.wAction = wAction;
        this.wObject = wObject;
        this.wParameter = wParameter;
        this.wExample = wExample;
        this.wCategory = wCategory;
        this.wContradiction = wContradiction;
        this.minConfidence = minConfidence;
        this.minMargin = minMargin;
        this.topK = topK;
        this.bm25Weight = bm25Weight;
        this.semanticWeight = semanticWeight;
    }

    public static ScoringWeights defaultWeights() {
        return new ScoringWeights();
    }

    public double getWLexical() { return wLexical; }
    public void setWLexical(double wLexical) { this.wLexical = wLexical; }

    public double getWSemantic() { return wSemantic; }
    public void setWSemantic(double wSemantic) { this.wSemantic = wSemantic; }

    public double getWAction() { return wAction; }
    public void setWAction(double wAction) { this.wAction = wAction; }

    public double getWObject() { return wObject; }
    public void setWObject(double wObject) { this.wObject = wObject; }

    public double getWParameter() { return wParameter; }
    public void setWParameter(double wParameter) { this.wParameter = wParameter; }

    public double getWExample() { return wExample; }
    public void setWExample(double wExample) { this.wExample = wExample; }

    public double getWCategory() { return wCategory; }
    public void setWCategory(double wCategory) { this.wCategory = wCategory; }

    public double getWContradiction() { return wContradiction; }
    public void setWContradiction(double wContradiction) { this.wContradiction = wContradiction; }

    public double getMinConfidence() { return minConfidence; }
    public void setMinConfidence(double minConfidence) { this.minConfidence = minConfidence; }

    public double getMinMargin() { return minMargin; }
    public void setMinMargin(double minMargin) { this.minMargin = minMargin; }

    public int getTopK() { return topK; }
    public void setTopK(int topK) { this.topK = topK; }

    public double getBm25Weight() { return bm25Weight; }
    public void setBm25Weight(double bm25Weight) { this.bm25Weight = bm25Weight; }

    public double getSemanticWeight() { return semanticWeight; }
    public void setSemanticWeight(double semanticWeight) { this.semanticWeight = semanticWeight; }

    @Override
    public String toString() {
        return "ScoringWeights{" +
                "wLexical=" + wLexical +
                ", wSemantic=" + wSemantic +
                ", wAction=" + wAction +
                ", wObject=" + wObject +
                ", wParameter=" + wParameter +
                ", wExample=" + wExample +
                ", wCategory=" + wCategory +
                ", wContradiction=" + wContradiction +
                ", minConfidence=" + minConfidence +
                ", minMargin=" + minMargin +
                ", topK=" + topK +
                ", bm25Weight=" + bm25Weight +
                ", semanticWeight=" + semanticWeight +
                '}';
    }
}
