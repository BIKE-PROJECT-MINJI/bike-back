package com.bikeprojectminji.bikeback.party.dto;

public record RidePartyReportResponse(
        Long partyId,
        long reportCount,
        String status
) {
}
