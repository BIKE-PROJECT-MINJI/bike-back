package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CourseRepository extends JpaRepository<CourseEntity, Long>, CourseRepositoryCustom {

    List<CourseEntity> findByCuratedTrueOrderByFeaturedRankAscIdAsc();

    Optional<CourseEntity> findTopByOrderByDisplayOrderDescIdDesc();

    Optional<CourseEntity> findByIdAndShareToken(Long id, String shareToken);

    List<CourseEntity> findTop20ByVisibilityOrderByIdDesc(CourseVisibility visibility);

    List<CourseEntity> findTop20ByVisibilityAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility visibility, String title);

    @Query("select count(c) from CourseEntity c where c.ownerUserId = :ownerUserId")
    long countByOwnerUserId(Long ownerUserId);

    @Query("select count(c) from CourseEntity c where c.ownerUserId = :ownerUserId and c.createdAt between :start and :end")
    long countByOwnerUserIdAndCreatedAtBetween(Long ownerUserId, OffsetDateTime start, OffsetDateTime end);

    default List<CourseEntity> findFeaturedCourses() {
        return findByCuratedTrueOrderByFeaturedRankAscIdAsc();
    }
}
