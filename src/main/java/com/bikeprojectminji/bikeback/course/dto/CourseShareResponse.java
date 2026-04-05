package com.bikeprojectminji.bikeback.course.dto;

public record CourseShareResponse(
        String shareType,
        String visibility,
        String shareUrl,
        String shareToken
) {
}
