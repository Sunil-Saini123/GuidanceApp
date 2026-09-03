package com.example.floatingassistant.intent.analysis;

import com.example.floatingassistant.intent.model.ExtractedParameter;
import com.example.floatingassistant.intent.model.IntentDefinition;
import com.example.floatingassistant.intent.model.QueryFeatures;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ContradictionDetector — Evaluates negative evidence and contradictions between
 * query semantics and candidate intent expectations.
 * Action mismatches, parameter contradictions, and domain mismatches produce strong penalties.
 */
public class ContradictionDetector {

    public static class ContradictionReport {
        private final double actionMismatch;
        private final double parameterContradiction;
        private final double objectMismatch;
        private final double totalPenalty;

        public ContradictionReport(double actionMismatch, double parameterContradiction, double objectMismatch) {
            this.actionMismatch = Math.max(0.0, Math.min(1.0, actionMismatch));
            this.parameterContradiction = Math.max(0.0, Math.min(1.0, parameterContradiction));
            this.objectMismatch = Math.max(0.0, Math.min(1.0, objectMismatch));
            // Weighted total penalty capped at 1.0
            this.totalPenalty = Math.min(1.0, (0.45 * this.actionMismatch) + (0.35 * this.parameterContradiction) + (0.20 * this.objectMismatch));
        }

        public double getActionMismatch() { return actionMismatch; }
        public double getParameterContradiction() { return parameterContradiction; }
        public double getObjectMismatch() { return objectMismatch; }
        public double getTotalPenalty() { return totalPenalty; }

        @Override
        public String toString() {
            return "ContradictionReport{" +
                    "actionMismatch=" + String.format("%.2f", actionMismatch) +
                    ", paramContradiction=" + String.format("%.2f", parameterContradiction) +
                    ", objectMismatch=" + String.format("%.2f", objectMismatch) +
                    ", totalPenalty=" + String.format("%.2f", totalPenalty) +
                    '}';
        }
    }

    /**
     * Analyzes contradictions between the query features and candidate intent definition.
     */
    public static ContradictionReport detectContradictions(QueryFeatures features, IntentDefinition candidate) {
        if (features == null || candidate == null) {
            return new ContradictionReport(0.0, 0.0, 0.0);
        }

        double actionMismatch = calculateActionMismatch(features.getDetectedAction(), candidate);
        double parameterContradiction = calculateParameterContradiction(features, candidate);
        double objectMismatch = calculateObjectMismatch(features.getDetectedObject(), candidate);

        return new ContradictionReport(actionMismatch, parameterContradiction, objectMismatch);
    }

    private static double calculateActionMismatch(String queryAction, IntentDefinition candidate) {
        if (queryAction.isEmpty()) return 0.0;

        String intentName = candidate.getIntentName().toUpperCase(Locale.US);

        // 1. Hard polar contradictions (ENABLE vs DISABLE)
        if ("ENABLE".equals(queryAction) && (intentName.startsWith("DISABLE") || intentName.contains("FORGET") || intentName.contains("REMOVE"))) {
            return 1.0;
        }
        if ("DISABLE".equals(queryAction) && intentName.startsWith("ENABLE")) {
            return 1.0;
        }

        // 2. CONNECT action mismatch: query is CONNECT, but intent is purely ENABLE or OPEN_SETTINGS
        if ("CONNECT".equals(queryAction)) {
            if (intentName.startsWith("ENABLE_") || intentName.startsWith("DISABLE_")) {
                return 0.85; // Strong penalty
            }
            if (intentName.startsWith("OPEN_") && !intentName.contains("CONNECT")) {
                return 0.60; // Moderate penalty
            }
            if (intentName.startsWith("CONNECT_")) {
                return 0.0; // Perfect match
            }
        }

        // 3. Check against candidate action aliases
        List<String> actionAliases = candidate.getActionAliases();
        if (!actionAliases.isEmpty()) {
            boolean aliasFound = false;
            for (String alias : actionAliases) {
                if (alias.equalsIgnoreCase(queryAction) || alias.contains(queryAction.toLowerCase(Locale.US))) {
                    aliasFound = true;
                    break;
                }
            }
            if (!aliasFound && (intentName.startsWith("CONNECT_") || intentName.startsWith("ENABLE_") || intentName.startsWith("DISABLE_"))) {
                return 0.50;
            }
        }

        return 0.0;
    }

    private static double calculateParameterContradiction(QueryFeatures features, IntentDefinition candidate) {
        boolean queryHasNetworkParam = features.hasParameter("network_name");
        boolean intentExpectsNetworkParam = candidate.hasParameter("network_name");
        boolean intentRequiresNetworkParam = false;

        Map<String, IntentDefinition.ParameterSpec> paramSpecs = candidate.getParameters();
        IntentDefinition.ParameterSpec netSpec = paramSpecs.get("network_name");
        if (netSpec != null && netSpec.isRequired()) {
            intentRequiresNetworkParam = true;
        }

        // Case A: Query provided specific network name (e.g. "Rohit Wi-Fi"), but intent expects NO parameters (e.g. ENABLE_WIFI)
        if (queryHasNetworkParam && !intentExpectsNetworkParam) {
            return 0.90; // Severe parameter contradiction
        }

        // Case B: Intent strictly requires a parameter, but query did not provide one (e.g. "Connect to Wi-Fi" with no name)
        if (intentRequiresNetworkParam && !queryHasNetworkParam) {
            return 0.40; // Moderate penalty for missing required parameter
        }

        // Case C: Query has percentage/numeric value, check compatibility
        boolean queryHasValue = features.hasParameter("value");
        boolean intentAcceptsValue = candidate.hasParameter("value");
        if (queryHasValue && !intentAcceptsValue && !candidate.getCategory().equalsIgnoreCase("Display")) {
            return 0.30;
        }

        return 0.0;
    }

    private static double calculateObjectMismatch(String queryDomain, IntentDefinition candidate) {
        if (queryDomain.isEmpty()) return 0.0;

        String category = candidate.getCategory().toLowerCase(Locale.US);
        List<String> aliases = candidate.getObjectAliases();

        // Check if candidate matches query domain
        for (String alias : aliases) {
            if (alias.toLowerCase(Locale.US).contains(queryDomain) || queryDomain.contains(alias.toLowerCase(Locale.US))) {
                return 0.0; // Matched domain
            }
        }

        if (category.contains(queryDomain) || queryDomain.contains(category)) {
            return 0.0; // Category matched
        }

        // Distinct domains mismatch (e.g. query is bluetooth, candidate is wifi)
        if (("wifi".equals(queryDomain) && category.contains("device")) ||
                ("bluetooth".equals(queryDomain) && category.contains("network")) ||
                ("display".equals(queryDomain) && category.contains("sound"))) {
            return 1.0; // Hard domain mismatch
        }

        return 0.40;
    }
}
