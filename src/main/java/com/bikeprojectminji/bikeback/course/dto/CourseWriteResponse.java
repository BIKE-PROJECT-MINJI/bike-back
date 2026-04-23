package com.bikeprojectminji.bikeback.course.dto;

public record CourseWriteResponse(
        Long courseId,
        Long ownerUserId,
        String visibility,
        String title,
        Long sourceRideRecordId
) {

    public CourseWriteResponse(Long courseId, Long ownerUserId, String visibility, String title) {
        this(courseId, ownerUserId, visibility, title, null);
    }
}
