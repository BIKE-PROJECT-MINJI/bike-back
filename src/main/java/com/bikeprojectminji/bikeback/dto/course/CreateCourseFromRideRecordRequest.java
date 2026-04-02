package com.bikeprojectminji.bikeback.dto.course;

public record CreateCourseFromRideRecordRequest(
        Long sourceRideRecordId,
        String name,
        String description,
        String visibility
) {
}
