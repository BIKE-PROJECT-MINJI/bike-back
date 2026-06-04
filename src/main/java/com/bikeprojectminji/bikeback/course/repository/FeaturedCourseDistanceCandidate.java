package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;

public record FeaturedCourseDistanceCandidate(
        CourseEntity course,
        Integer distanceFromUserM
) {
}
