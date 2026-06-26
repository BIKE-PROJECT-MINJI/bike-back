package com.bikeprojectminji.bikeback.course.dto;

import java.time.OffsetDateTime;

public record CoursePublicationResponse(
        Long publicationId,
        Long courseId,
        Long ownerUserId,
        String status,
        OffsetDateTime publishedAt,
        OffsetDateTime unpublishedAt
) {
}
