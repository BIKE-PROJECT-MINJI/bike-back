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
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
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
    public List<CourseListRow> findPublicListPageAfter(Long cursorId, int limitPlusOne) {
        if (cursorId == null) {
            Query query = entityManager.createNativeQuery("""
                    select course_id, title, distance_km, estimated_duration_min
                    from course_list_summaries
                    order by display_order asc, course_id asc
                    limit :limit
                    """);
            query.setParameter("limit", limitPlusOne);
            return toCourseListRows(query.getResultList());
        }

        List<?> anchors = entityManager.createNativeQuery("""
                        select course_id, display_order
                        from course_list_summaries where course_id = :id
                        """)
                .setParameter("id", cursorId)
                .getResultList();
        if (anchors.isEmpty()) {
            return Collections.emptyList();
        }

        CoursePageCursorAnchor anchor = toCursorAnchor(anchors.get(0));
        Query query = entityManager.createNativeQuery("""
                select course_id, title, distance_km, estimated_duration_min
                from course_list_summaries
                where (
                    display_order > :displayOrder
                    or (display_order = :displayOrder and course_id > :id)
                )
                order by display_order asc, course_id asc
                limit :limit
                """);
        query.setParameter("displayOrder", anchor.displayOrder());
        query.setParameter("id", anchor.id());
        query.setParameter("limit", limitPlusOne);
        return toCourseListRows(query.getResultList());
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
        List<FeaturedCourseDistanceRow> distanceRows = new ArrayList<>();
        for (Object row : rows) {
            Object[] columns = (Object[]) row;
            distanceRows.add(new FeaturedCourseDistanceRow(
                    ((Number) columns[0]).longValue(),
                    ((Number) columns[1]).intValue()
            ));
        }
        if (distanceRows.isEmpty()) {
            return List.of();
        }

        List<Long> courseIds = distanceRows.stream()
                .map(FeaturedCourseDistanceRow::courseId)
                .toList();
        Map<Long, CourseEntity> coursesById = entityManager.createQuery(
                        "select c from CourseEntity c where c.id in :ids",
                        CourseEntity.class
                )
                .setParameter("ids", courseIds)
                .getResultList()
                .stream()
                .collect(Collectors.toMap(CourseEntity::getId, Function.identity()));

        List<FeaturedCourseDistanceCandidate> candidates = new ArrayList<>();
        for (FeaturedCourseDistanceRow distanceRow : distanceRows) {
            CourseEntity course = coursesById.get(distanceRow.courseId());
            if (course != null) {
                candidates.add(new FeaturedCourseDistanceCandidate(course, distanceRow.distanceFromUserM()));
            }
        }
        return candidates;
    }

    private record FeaturedCourseDistanceRow(Long courseId, Integer distanceFromUserM) {
    }

    private List<CourseListRow> toCourseListRows(List<?> rawRows) {
        List<CourseListRow> rows = new ArrayList<>();
        for (Object rawRow : rawRows) {
            Object[] columns = (Object[]) rawRow;
            rows.add(new CourseListRow(
                    ((Number) columns[0]).longValue(),
                    (String) columns[1],
                    (BigDecimal) columns[2],
                    ((Number) columns[3]).intValue()
            ));
        }
        return rows;
    }

    private CoursePageCursorAnchor toCursorAnchor(Object rawRow) {
        Object[] columns = (Object[]) rawRow;
        return new CoursePageCursorAnchor(
                ((Number) columns[0]).longValue(),
                ((Number) columns[1]).intValue()
        );
    }
}
