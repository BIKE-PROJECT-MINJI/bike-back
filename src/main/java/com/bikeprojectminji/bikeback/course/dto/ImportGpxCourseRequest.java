package com.bikeprojectminji.bikeback.course.dto;

public record ImportGpxCourseRequest(
        String title,
        String description,
        String visibility,
        String gpx
) {
}
