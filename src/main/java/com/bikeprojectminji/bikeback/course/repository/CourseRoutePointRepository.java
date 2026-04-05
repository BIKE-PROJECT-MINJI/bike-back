package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRoutePointRepository extends JpaRepository<CourseRoutePointEntity, Long> {

    List<CourseRoutePointEntity> findByCourseIdOrderByPointOrderAsc(Long courseId);

    void deleteByCourseId(Long courseId);
}
