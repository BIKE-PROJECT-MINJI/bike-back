package com.bikeprojectminji.bikeback.dto.course;

public record CourseShareResponse(
        String shareType,
        String visibility,
        String shareUrl,
        String shareToken
) {
}
