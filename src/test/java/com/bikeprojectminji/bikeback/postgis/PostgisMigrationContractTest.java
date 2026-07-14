package com.bikeprojectminji.bikeback.postgis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("postgis")
@Testcontainers
class PostgisMigrationContractTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("bike_postgis_contract")
            .withUsername("bike_test")
            .withPassword("bike_test");

    private static int appliedMigrationCount;

    @BeforeAll
    static void migrateEmptyDatabase() {
        appliedMigrationCount = Flyway.configure()
                .dataSource(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate()
                .migrationsExecuted;
    }

    @Test
    @DisplayName("빈 PostGIS 데이터베이스에 운영 Flyway migration 전체가 적용된다")
    void appliesAllProductionMigrationsToEmptyPostgis() throws SQLException {
        assertThat(appliedMigrationCount).isEqualTo(35);

        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     select count(*) as migration_count,
                            max(version::integer) as latest_version,
                            bool_and(success) as all_succeeded
                     from flyway_schema_history
                     where type = 'SQL'
                     """)) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt("migration_count")).isEqualTo(35);
            assertThat(result.getInt("latest_version")).isEqualTo(35);
            assertThat(result.getBoolean("all_succeeded")).isTrue();
        }
    }

    @Test
    @DisplayName("PostGIS extension과 핵심 geometry 컬럼은 SRID 4326으로 생성된다")
    void createsPostgisGeometryColumnsWithSrid4326() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(querySingleString(connection,
                    "select extversion from pg_extension where extname = 'postgis'"))
                    .isNotBlank();

            Map<String, GeometryColumn> actual = queryGeometryColumns(connection);
            assertThat(actual).containsAllEntriesOf(Map.of(
                    "courses.start_point_geom", new GeometryColumn("POINT", 4326),
                    "courses.route_line_geom", new GeometryColumn("LINESTRING", 4326),
                    "course_route_points.point_geom", new GeometryColumn("POINT", 4326),
                    "ride_record_points.point_geom", new GeometryColumn("POINT", 4326),
                    "ride_record_processed_points.point_geom", new GeometryColumn("POINT", 4326)
            ));
        }
    }

    @Test
    @DisplayName("핵심 geometry 컬럼의 GiST 인덱스가 실제 PostgreSQL에 존재한다")
    void createsExpectedGistIndexes() throws SQLException {
        Set<String> expectedIndexes = Set.of(
                "idx_courses_start_point_geom_gist",
                "idx_courses_route_line_geom_gist",
                "idx_course_route_points_point_geom_gist",
                "idx_ride_record_points_point_geom_gist",
                "idx_ride_record_processed_points_point_geom_gist"
        );

        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     select indexname, indexdef
                     from pg_indexes
                     where schemaname = 'public' and indexname = any (?)
                     """)) {
            statement.setArray(1, connection.createArrayOf("text", expectedIndexes.toArray()));
            try (ResultSet result = statement.executeQuery()) {
                Map<String, String> actual = new java.util.HashMap<>();
                while (result.next()) {
                    actual.put(result.getString("indexname"), result.getString("indexdef"));
                }
                assertThat(actual.keySet()).containsExactlyInAnyOrderElementsOf(expectedIndexes);
                assertThat(actual.values()).allMatch(definition -> definition.contains("USING gist"));
            }
        }
    }

    @Test
    @DisplayName("ST_MakeLine은 삽입 순서가 아니라 point_order 순서로 LineString을 만든다")
    void createsRouteLineInPointOrder() throws SQLException {
        try (Connection connection = connection()) {
            long courseId = insertSyntheticCourse(connection);
            insertRoutePoint(connection, courseId, 3, "37.5030000", "127.0030000");
            insertRoutePoint(connection, courseId, 1, "37.5010000", "127.0010000");
            insertRoutePoint(connection, courseId, 2, "37.5020000", "127.0020000");

            try (PreparedStatement statement = connection.prepareStatement("""
                    update courses c
                    set route_line_geom = route_line.route_line_geom
                    from (
                        select course_id,
                               ST_MakeLine(point_geom order by point_order)::geometry(LineString, 4326) as route_line_geom
                        from course_route_points
                        where course_id = ?
                        group by course_id
                    ) route_line
                    where c.id = route_line.course_id
                    """)) {
                statement.setLong(1, courseId);
                assertThat(statement.executeUpdate()).isEqualTo(1);
            }

            try (PreparedStatement statement = connection.prepareStatement("""
                    select ST_SRID(route_line_geom) as srid,
                           ST_NPoints(route_line_geom) as point_count,
                           ST_X(ST_PointN(route_line_geom, 1)) as first_lon,
                           ST_Y(ST_PointN(route_line_geom, 1)) as first_lat,
                           ST_X(ST_PointN(route_line_geom, 3)) as last_lon,
                           ST_Y(ST_PointN(route_line_geom, 3)) as last_lat
                    from courses
                    where id = ?
                    """)) {
                statement.setLong(1, courseId);
                try (ResultSet result = statement.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getInt("srid")).isEqualTo(4326);
                    assertThat(result.getInt("point_count")).isEqualTo(3);
                    assertThat(result.getBigDecimal("first_lon")).isEqualByComparingTo("127.001");
                    assertThat(result.getBigDecimal("first_lat")).isEqualByComparingTo("37.501");
                    assertThat(result.getBigDecimal("last_lon")).isEqualByComparingTo("127.003");
                    assertThat(result.getBigDecimal("last_lat")).isEqualByComparingTo("37.503");
                }
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGIS.getJdbcUrl(), POSTGIS.getUsername(), POSTGIS.getPassword());
    }

    private static String querySingleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private static Map<String, GeometryColumn> queryGeometryColumns(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                     select f_table_name, f_geometry_column, type, srid
                     from geometry_columns
                     where f_table_schema = 'public'
                     """)) {
            Map<String, GeometryColumn> columns = new java.util.HashMap<>();
            while (result.next()) {
                String key = result.getString("f_table_name") + "." + result.getString("f_geometry_column");
                columns.put(key, new GeometryColumn(result.getString("type"), result.getInt("srid")));
            }
            return columns;
        }
    }

    private static long insertSyntheticCourse(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into courses (title, distance_km, estimated_duration_min, display_order)
                values ('synthetic-postgis-contract', 1.0, 5, 9999)
                returning id
                """)) {
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private static void insertRoutePoint(
            Connection connection,
            long courseId,
            int pointOrder,
            String latitude,
            String longitude
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into course_route_points (course_id, point_order, latitude, longitude)
                values (?, ?, ?, ?)
                """)) {
            statement.setLong(1, courseId);
            statement.setInt(2, pointOrder);
            statement.setBigDecimal(3, new BigDecimal(latitude));
            statement.setBigDecimal(4, new BigDecimal(longitude));
            statement.executeUpdate();
        }
    }

    private record GeometryColumn(String type, int srid) {
    }
}
