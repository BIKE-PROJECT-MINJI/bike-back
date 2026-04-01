package com.bikeprojectminji.bikeback.repository.course;

import com.bikeprojectminji.bikeback.entity.course.CourseRoutePointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRoutePointRepository extends JpaRepository<CourseRoutePointEntity, Long> {

    List<CourseRoutePointEntity> findByCourseIdOrderByPointOrderAsc(Long courseId);
}
