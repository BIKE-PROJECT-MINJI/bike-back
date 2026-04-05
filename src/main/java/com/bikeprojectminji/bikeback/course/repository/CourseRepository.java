package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<CourseEntity, Long>, CourseRepositoryCustom {

    List<CourseEntity> findByCuratedTrueOrderByFeaturedRankAscIdAsc();

    Optional<CourseEntity> findTopByOrderByDisplayOrderDescIdDesc();

    Optional<CourseEntity> findByIdAndShareToken(Long id, String shareToken);

    List<CourseEntity> findTop20ByVisibilityOrderByIdDesc(CourseVisibility visibility);

    List<CourseEntity> findTop20ByVisibilityAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility visibility, String title);

    default List<CourseEntity> findFeaturedCourses() {
        return findByCuratedTrueOrderByFeaturedRankAscIdAsc();
    }
}
