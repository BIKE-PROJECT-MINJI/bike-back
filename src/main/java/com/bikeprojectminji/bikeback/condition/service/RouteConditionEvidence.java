package com.bikeprojectminji.bikeback.condition.service;

public record RouteConditionEvidence(
        String source,
        String label,
        String status,
        String severity,
        String summary,
        String observedAt
) {

    public static RouteConditionEvidence verified(String source, String label, String summary) {
        return new RouteConditionEvidence(source, label, "VERIFIED", "INFO", summary, null);
    }

    public static RouteConditionEvidence warning(String source, String label, String summary, String severity) {
        return new RouteConditionEvidence(source, label, "WARNING", severity, summary, null);
    }

    public static RouteConditionEvidence unknown(String source, String label, String summary) {
        return new RouteConditionEvidence(source, label, "UNKNOWN", "UNKNOWN", summary, null);
    }

    public static RouteConditionEvidence failed(String source, String label, String summary) {
        return new RouteConditionEvidence(source, label, "FAILED", "UNKNOWN", summary, null);
    }
}
