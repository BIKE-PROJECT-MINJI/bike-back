package com.bikeprojectminji.bikeback.course.dto;

public record CourseReportResponse(
        Long courseId,
        long reportCount,
        boolean reportHidden,
        String reportHiddenReason
) {
}
