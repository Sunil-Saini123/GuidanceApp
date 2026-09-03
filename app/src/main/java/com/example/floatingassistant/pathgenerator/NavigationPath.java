package com.example.floatingassistant.pathgenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * NavigationPath — Encapsulates the resolved navigation path steps,
 * destination, validity flag, and formatting methods for NavigationStateMachine.
 */
public class NavigationPath {

    private final String destination;
    private final List<String> steps;
    private final boolean valid;
    private final String errorMessage;
    private final String rawGroqResponse;

    public NavigationPath(String destination, List<String> steps, boolean valid, String errorMessage, String rawGroqResponse) {
        this.destination = destination != null ? destination.trim() : "";
        this.steps = steps != null ? new ArrayList<>(steps) : Collections.emptyList();
        this.valid = valid && !this.steps.isEmpty();
        this.errorMessage = errorMessage != null ? errorMessage : "";
        this.rawGroqResponse = rawGroqResponse != null ? rawGroqResponse : "";
    }

    public static NavigationPath success(String destination, List<String> steps, String rawGroqResponse) {
        return new NavigationPath(destination, steps, true, null, rawGroqResponse);
    }

    public static NavigationPath failure(String errorMessage) {
        return new NavigationPath("", Collections.emptyList(), false, errorMessage, null);
    }

    public String getDestination() {
        return destination;
    }

    public List<String> getSteps() {
        return Collections.unmodifiableList(steps);
    }

    public boolean isValid() {
        return valid;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getRawGroqResponse() {
        return rawGroqResponse;
    }

    /**
     * Formats steps into the standard arrow-separated string format expected by NavigationStateMachine:
     * e.g., "Settings -> Connected devices -> Bluetooth"
     */
    public String toPathString() {
        if (steps.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            builder.append(steps.get(i));
            if (i < steps.size() - 1) {
                builder.append(" -> ");
            }
        }
        return builder.toString();
    }

    @Override
    public String toString() {
        return "NavigationPath{" +
                "destination='" + destination + '\'' +
                ", steps=" + steps +
                ", valid=" + valid +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
