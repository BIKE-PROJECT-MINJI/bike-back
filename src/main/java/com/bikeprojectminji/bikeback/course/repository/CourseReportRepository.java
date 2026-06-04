package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseReportEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseReportRepository extends JpaRepository<CourseReportEntity, Long> {

    boolean existsByCourseIdAndReporterUserId(Long courseId, Long reporterUserId);

    long countByCourseId(Long courseId);
}
