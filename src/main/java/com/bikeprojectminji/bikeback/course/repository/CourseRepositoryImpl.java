package com.bikeprojectminji.bikeback.course.repository;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Repository;

@Repository
public class CourseRepositoryImpl implements CourseRepositoryCustom {

    private final EntityManager entityManager;

    public CourseRepositoryImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<CourseEntity> findPublicPageAfter(Long cursorId, int limitPlusOne) {
        if (cursorId == null) {
            TypedQuery<CourseEntity> query = entityManager.createQuery(
                    "select c from CourseEntity c where c.visibility = :visibility and c.reportHidden = false order by c.displayOrder asc, c.id asc",
                    CourseEntity.class
            );
            query.setParameter("visibility", CourseVisibility.PUBLIC);
            query.setMaxResults(limitPlusOne);
            return query.getResultList();
        }

        Optional<CourseEntity> anchor = Optional.ofNullable(entityManager.find(CourseEntity.class, cursorId));
        if (anchor.isEmpty()) {
            return Collections.emptyList();
        }

        TypedQuery<CourseEntity> query = entityManager.createQuery(
                "select c from CourseEntity c " +
                        "where c.visibility = :visibility and c.reportHidden = false and (c.displayOrder > :displayOrder " +
                        "or (c.displayOrder = :displayOrder and c.id > :id)) " +
                        "order by c.displayOrder asc, c.id asc",
                CourseEntity.class
        );
        query.setParameter("visibility", CourseVisibility.PUBLIC);
        query.setParameter("displayOrder", anchor.get().getDisplayOrder());
        query.setParameter("id", anchor.get().getId());
        query.setMaxResults(limitPlusOne);
        return query.getResultList();
    }

    @Override
    public List<FeaturedCourseDistanceCandidate> findFeaturedCoursesNear(BigDecimal lat, BigDecimal lon, int limit) {
        if (lat == null || lon == null || limit < 1) {
            return List.of();
        }

        Query query = entityManager.createNativeQuery("""
                select
                    c.id,
                    cast(round(ST_DistanceSphere(
                        c.start_point_geom,
                        ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)
                    )) as integer) as distance_m
                from courses c
                where c.curated = true
                  and c.visibility = 'PUBLIC'
                  and c.report_hidden = false
                  and c.start_point_geom is not null
                  and c.start_point_geom && ST_Expand(ST_SetSRID(ST_MakePoint(:lon, :lat), 4326), 1.0)
                order by
                    c.start_point_geom <-> ST_SetSRID(ST_MakePoint(:lon, :lat), 4326),
                    ST_DistanceSphere(c.start_point_geom, ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)) asc,
                    c.featured_rank asc nulls last,
                    c.id asc
                limit :limit
                """);
        query.setParameter("lat", lat);
        query.setParameter("lon", lon);
        query.setParameter("limit", limit);

        List<?> rows = query.getResultList();
        List<FeaturedCourseDistanceCandidate> candidates = new ArrayList<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            Long courseId = ((Number) columns[0]).longValue();
            CourseEntity course = entityManager.find(CourseEntity.class, courseId);
            if (course != null) {
                candidates.add(new FeaturedCourseDistanceCandidate(course, ((Number) columns[1]).intValue()));
            }
        }
        return candidates;
    }
}
