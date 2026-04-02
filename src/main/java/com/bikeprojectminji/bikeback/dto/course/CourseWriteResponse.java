package com.bikeprojectminji.bikeback.dto.course;

public record CourseWriteResponse(
        Long courseId,
        Long ownerUserId,
        String visibility,
        String title
) {
}
