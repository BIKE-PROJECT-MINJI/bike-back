package com.bikeprojectminji.bikeback.course.dto;

public record CourseWriteResponse(
        Long courseId,
        Long ownerUserId,
        String visibility,
        String title,
        Long sourceRideRecordId,
        boolean sourceDetached
) {

    public CourseWriteResponse(Long courseId, Long ownerUserId, String visibility, String title) {
        this(courseId, ownerUserId, visibility, title, null, false);
    }

    public CourseWriteResponse(Long courseId, Long ownerUserId, String visibility, String title, Long sourceRideRecordId) {
        this(courseId, ownerUserId, visibility, title, sourceRideRecordId, false);
    }
}
