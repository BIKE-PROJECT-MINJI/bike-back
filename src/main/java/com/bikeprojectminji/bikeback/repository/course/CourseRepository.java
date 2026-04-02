package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<CourseEntity, Long>, CourseRepositoryCustom {

    List<CourseEntity> findByCuratedTrueOrderByFeaturedRankAscIdAsc();

    Optional<CourseEntity> findTopByOrderByDisplayOrderDescIdDesc();

    default List<CourseEntity> findFeaturedCourses() {
        return findByCuratedTrueOrderByFeaturedRankAscIdAsc();
    }
}
