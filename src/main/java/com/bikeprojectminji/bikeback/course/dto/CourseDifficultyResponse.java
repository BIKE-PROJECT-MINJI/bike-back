package com.bikeprojectminji.bikeback.course.dto;

public record CourseDifficultyResponse(
        String level,
        String label,
        int score,
        String summary
) {
}
