package com.bikeprojectminji.bikeback.course.entity;

public enum CourseReportReason {
    INACCURATE_ROUTE(false),
    UNSAFE_SURFACE(false),
    INAPPROPRIATE_CONTENT(false),
    PRIVATE_PROPERTY_OR_CLOSED_ROAD(true);

    private final boolean highRisk;

    CourseReportReason(boolean highRisk) {
        this.highRisk = highRisk;
    }

    public boolean isHighRisk() {
        return highRisk;
    }
}
