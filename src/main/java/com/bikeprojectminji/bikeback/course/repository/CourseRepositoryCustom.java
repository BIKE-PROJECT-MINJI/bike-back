package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import java.math.BigDecimal;
import java.util.List;

public interface CourseRepositoryCustom {

    List<CourseEntity> findPublicPageAfter(Long cursorId, int limitPlusOne);

    List<FeaturedCourseDistanceCandidate> findFeaturedCoursesNear(BigDecimal lat, BigDecimal lon, int limit);
}
