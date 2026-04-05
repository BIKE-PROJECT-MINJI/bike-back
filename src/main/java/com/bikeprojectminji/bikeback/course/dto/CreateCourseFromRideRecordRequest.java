package com.bikeprojectminji.bikeback.course.dto;

public record CreateCourseFromRideRecordRequest(
        Long sourceRideRecordId,
        String name,
        String description,
        String visibility
) {
}
