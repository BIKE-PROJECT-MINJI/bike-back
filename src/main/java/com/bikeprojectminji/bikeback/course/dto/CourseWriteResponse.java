package com.bikeprojectminji.bikeback.course.dto;

public record CourseWriteResponse(
        Long courseId,
        Long ownerUserId,
        String visibility,
        String title
) {
}
