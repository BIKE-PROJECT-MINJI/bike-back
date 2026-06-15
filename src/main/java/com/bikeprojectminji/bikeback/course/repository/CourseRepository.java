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

    Optional<CourseEntity> findTopByOwnerUserIdAndSourceRideRecordIdOrderByIdDesc(Long ownerUserId, Long sourceRideRecordId);

    List<CourseEntity> findByOwnerUserId(Long ownerUserId);

    List<CourseEntity> findByOwnerUserIdAndSourceRideRecordIdIn(Long ownerUserId, List<Long> sourceRideRecordIds);

    List<CourseEntity> findBySourceRideRecordIdIn(List<Long> sourceRideRecordIds);

    List<CourseEntity> findTop20ByVisibilityAndReportHiddenFalseOrderByIdDesc(CourseVisibility visibility);

    List<CourseEntity> findTop20ByVisibilityAndReportHiddenFalseAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility visibility, String title);

    default List<CourseEntity> findTop20ByVisibilityOrderByIdDesc(CourseVisibility visibility) {
        return findTop20ByVisibilityAndReportHiddenFalseOrderByIdDesc(visibility);
    }

    default List<CourseEntity> findTop20ByVisibilityAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility visibility, String title) {
        return findTop20ByVisibilityAndReportHiddenFalseAndTitleContainingIgnoreCaseOrderByIdDesc(visibility, title);
    }

    @Query("""
            select new com.bikeprojectminji.bikeback.course.repository.CourseActivityAggregate(
                coalesce(sum(case when c.createdAt between :start and :end then 1 else 0 end), 0)
            )
            from CourseEntity c
            where c.ownerUserId = :ownerUserId
    """)
    CourseActivityAggregate findActivityAggregateByOwnerUserId(Long ownerUserId, OffsetDateTime start, OffsetDateTime end);

    default List<CourseEntity> findFeaturedCourses() {
        return findByCuratedTrueAndVisibilityAndReportHiddenFalseOrderByFeaturedRankAscIdAsc(CourseVisibility.PUBLIC);
    }

    List<CourseEntity> findByCuratedTrueAndVisibilityAndReportHiddenFalseOrderByFeaturedRankAscIdAsc(CourseVisibility visibility);
}
