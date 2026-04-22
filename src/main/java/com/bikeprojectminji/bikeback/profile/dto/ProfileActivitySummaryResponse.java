package com.bikeprojectminji.bikeback.profile.dto;

public record ProfileActivitySummaryResponse(
        ProfileWeeklyActivitySummaryResponse weeklySummary,
        ProfileOverallActivitySummaryResponse overallSummary
) {
}
