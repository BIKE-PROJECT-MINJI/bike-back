package com.bikeprojectminji.bikeback.course.repository;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCourseRouteGeometryRepository implements CourseRouteGeometryRepository {

    private static final String POSTGRESQL = "postgresql";

    private final JdbcTemplate jdbcTemplate;

    public JdbcCourseRouteGeometryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public int refreshRouteLine(Long courseId) {
        if (courseId == null || !isPostgreSql()) {
            return 0;
        }

        clearRouteLine(courseId);
        return jdbcTemplate.update("""
                update courses c
                set route_line_geom = route_line.route_line_geom
                from (
                    select
                        course_id,
                        ST_MakeLine(point_geom order by point_order)::geometry(LineString, 4326) as route_line_geom
                    from course_route_points
                    where course_id = ?
                    group by course_id
                    having count(*) >= 2
                ) route_line
                where c.id = route_line.course_id
                """, courseId);
    }

    private void clearRouteLine(Long courseId) {
        jdbcTemplate.update("update courses set route_line_geom = null where id = ?", courseId);
    }

    private boolean isPostgreSql() {
        return Boolean.TRUE.equals(jdbcTemplate.execute((ConnectionCallback<Boolean>) this::isPostgreSql));
    }

    private boolean isPostgreSql(Connection connection) throws SQLException {
        return connection.getMetaData()
                .getDatabaseProductName()
                .toLowerCase(Locale.ROOT)
                .contains(POSTGRESQL);
    }
}
