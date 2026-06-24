package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CoursePublicationEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CoursePublicationRepository extends JpaRepository<CoursePublicationEntity, Long> {

    Optional<CoursePublicationEntity> findByCourseId(Long courseId);
}
