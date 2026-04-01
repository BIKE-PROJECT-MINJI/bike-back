package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<CourseEntity, Long>, CourseRepositoryCustom {

    List<CourseEntity> findByCuratedTrueOrderByFeaturedRankAscIdAsc();

    default List<CourseEntity> findFeaturedCourses() {
        return findByCuratedTrueOrderByFeaturedRankAscIdAsc();
    }
}
