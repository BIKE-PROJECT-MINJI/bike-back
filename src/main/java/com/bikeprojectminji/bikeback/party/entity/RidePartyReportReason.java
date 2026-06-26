package com.bikeprojectminji.bikeback.party.entity;

public enum RidePartyReportReason {
    INAPPROPRIATE_CONTENT(false),
    SPAM_OR_COMMERCIAL(false),
    UNSAFE_MEETUP(false),
    HARASSMENT_OR_THREAT(true);

    private final boolean highRisk;

    RidePartyReportReason(boolean highRisk) {
        this.highRisk = highRisk;
    }

    public boolean isHighRisk() {
        return highRisk;
    }
}
