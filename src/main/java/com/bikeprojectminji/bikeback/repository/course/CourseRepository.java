package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import com.bikeprojectminji.bikeback.entity.course.CourseVisibility;
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
