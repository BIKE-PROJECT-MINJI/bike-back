package com.bikeprojectminji.bikeback.condition.service;

import java.util.List;

public record RouteConditionReport(
        String status,
        boolean startAllowed,
        int unknownCount,
        int failureCount,
        String llmEvidenceRule,
        List<RouteConditionEvidence> evidence
) {
}
