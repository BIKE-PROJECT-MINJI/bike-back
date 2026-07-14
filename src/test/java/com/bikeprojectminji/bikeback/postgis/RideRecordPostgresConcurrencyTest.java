package com.bikeprojectminji.bikeback.postgis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.ImportGpxCourseRequest;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.course.service.CourseQueryService;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.service.RideRecordService;
import com.bikeprojectminji.bikeback.ride.service.RideRecordDeletionService;
import java.math.BigDecimal;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("postgis")
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.sql.init.mode=never",
        "spring.jpa.hibernate.ddl-auto=none",
        "bike.app-role=api",
        "ride.save.max-concurrency=10"
})
class RideRecordPostgresConcurrencyTest {

    private static final int CONCURRENT_REQUESTS = 10;
    private static final String SUBJECT = "synthetic-contract-owner";
    private static final String CLIENT_RIDE_ID = "synthetic-concurrent-ride-001";
    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer<?> POSTGIS = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("bike_ride_concurrency")
            .withUsername("bike_test")
            .withPassword("bike_test");

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGIS::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGIS::getUsername);
        registry.add("spring.datasource.password", POSTGIS::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private RideRecordService rideRecordService;

    @Autowired
    private CourseService courseService;

    @Autowired
    private CourseQueryService courseQueryService;

    @Autowired
    private RideRecordDeletionService rideRecordDeletionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RecentLocationCacheStore recentLocationCacheStore;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    private UserEntity owner;

    @BeforeEach
    void setUpSyntheticOwnerAndRedisFailure() {
        jdbcTemplate.update("delete from ride_finalization_jobs");
        jdbcTemplate.update("delete from ride_record_points");
        jdbcTemplate.update("delete from ride_records");
        jdbcTemplate.update("delete from courses where owner_user_id in (select id from users where external_id = ?)", SUBJECT);
        jdbcTemplate.update("delete from users where external_id = ?", SUBJECT);

        owner = userRepository.saveAndFlush(new UserEntity(
                SUBJECT,
                "synthetic-contract-owner@example.invalid",
                "not-a-real-password-hash",
                "synthetic-owner",
                null
        ));
        given(authService.findUserBySubject(SUBJECT)).willReturn(owner);
        given(stringRedisTemplate.opsForValue())
                .willThrow(new RedisConnectionFailureException("synthetic Redis outage"));
    }

    @Test
    @DisplayName("Redis 잠금이 없어도 같은 clientRideId 10개 요청은 한 주행과 한 job으로 수렴한다")
    void convergesConcurrentRetriesThroughPostgresUniqueConstraint() throws Exception {
        CreateRideRecordRequest request = syntheticRequest();
        CyclicBarrier startBarrier = new CyclicBarrier(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);

        try {
            List<Callable<RideRecordResponse>> calls = new ArrayList<>();
            for (int index = 0; index < CONCURRENT_REQUESTS; index++) {
                calls.add(() -> {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    return rideRecordService.saveRideRecord(SUBJECT, request);
                });
            }

            List<Future<RideRecordResponse>> futures = executor.invokeAll(calls, 20, TimeUnit.SECONDS);
            List<RideRecordResponse> responses = new ArrayList<>();
            for (Future<RideRecordResponse> future : futures) {
                assertThat(future.isCancelled()).isFalse();
                responses.add(future.get(5, TimeUnit.SECONDS));
            }

            Set<Long> rideRecordIds = responses.stream()
                    .map(RideRecordResponse::rideRecordId)
                    .collect(java.util.stream.Collectors.toSet());
            assertThat(rideRecordIds).hasSize(1);
            assertThat(responses).allSatisfy(response -> {
                assertThat(response.ownerUserId()).isEqualTo(owner.getId());
                assertThat(response.routePointCount()).isEqualTo(3);
                assertThat(response.finalizationStatus()).isEqualTo("FINALIZING");
            });

            Long rideRecordId = rideRecordIds.iterator().next();
            assertThat(count("select count(*) from ride_records where owner_user_id = ? and client_ride_id = ?",
                    owner.getId(), CLIENT_RIDE_ID)).isEqualTo(1);
            assertThat(count("select count(*) from ride_record_points where ride_record_id = ?", rideRecordId))
                    .isEqualTo(3);
            assertThat(count("select count(*) from ride_finalization_jobs where ride_record_id = ?", rideRecordId))
                    .isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("정상 GPX import는 course, route point, SRID 4326 LineString을 한 트랜잭션에 저장한다")
    void importsGpxIntoPostgisGeometry() throws IOException {
        CourseWriteResponse response = courseService.importGpxCourse(SUBJECT, new ImportGpxCourseRequest(
                "synthetic-gpx-contract",
                "non-user fixture",
                "PRIVATE",
                fixture("synthetic-normal.gpx")
        ));

        assertThat(response.ownerUserId()).isEqualTo(owner.getId());
        assertThat(response.visibility()).isEqualTo("PRIVATE");
        assertThat(count("select count(*) from courses where id = ?", response.courseId())).isEqualTo(1);
        assertThat(count("select count(*) from course_route_points where course_id = ?", response.courseId()))
                .isEqualTo(3);
        assertThat(count("""
                select count(*) from courses
                where id = ?
                  and route_line_geom is not null
                  and ST_SRID(route_line_geom) = 4326
                  and ST_NPoints(route_line_geom) = 3
                """, response.courseId())).isEqualTo(1);
    }

    @Test
    @DisplayName("범위를 벗어난 GPX 좌표는 course와 route point를 남기지 않는다")
    void rejectsOutOfRangeGpxWithoutPartialPersistence() throws IOException {
        String title = "synthetic-invalid-gpx-contract";

        assertThatThrownBy(() -> courseService.importGpxCourse(SUBJECT, new ImportGpxCourseRequest(
                title,
                "non-user fixture",
                "PRIVATE",
                fixture("synthetic-out-of-range.gpx")
        )))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("-90 이상 90 이하");

        assertThat(count("select count(*) from courses where title = ? and owner_user_id = ?", title, owner.getId()))
                .isZero();
    }

    @Test
    @DisplayName("실제 PostgreSQL에서도 비소유자는 PRIVATE 코스와 타인 주행에 접근할 수 없다")
    void deniesCrossOwnerCourseAndRideAccess() throws IOException {
        String otherSubject = "synthetic-contract-other";
        jdbcTemplate.update("delete from users where external_id = ?", otherSubject);
        UserEntity other = userRepository.saveAndFlush(new UserEntity(
                otherSubject,
                "synthetic-contract-other@example.invalid",
                "not-a-real-password-hash",
                "synthetic-other",
                null
        ));
        given(authService.findUserBySubject(otherSubject)).willReturn(other);

        CourseWriteResponse privateCourse = courseService.importGpxCourse(SUBJECT, new ImportGpxCourseRequest(
                "synthetic-private-ownership-contract",
                "non-user fixture",
                "PRIVATE",
                fixture("synthetic-normal.gpx")
        ));
        RideRecordResponse ownerRide = rideRecordService.saveRideRecord(SUBJECT, new CreateRideRecordRequest(
                "synthetic-owner-only-ride",
                OffsetDateTime.parse("2026-01-15T01:00:00Z"),
                OffsetDateTime.parse("2026-01-15T01:05:00Z"),
                new RideRecordSummaryRequest(1200, 300),
                syntheticRequest().routePoints()
        ));

        assertThatThrownBy(() -> courseQueryService.getCourseDetail(privateCourse.courseId(), otherSubject, null))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> rideRecordService.getRideRecordStatus(otherSubject, ownerRide.rideRecordId()))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rideRecordDeletionService.deleteRideRecord(otherSubject, ownerRide.rideRecordId()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(count("select count(*) from courses where id = ?", privateCourse.courseId())).isEqualTo(1);
        assertThat(count("select count(*) from ride_records where id = ?", ownerRide.rideRecordId())).isEqualTo(1);
    }

    private CreateRideRecordRequest syntheticRequest() {
        OffsetDateTime startedAt = OffsetDateTime.parse("2026-01-15T01:00:00Z");
        return new CreateRideRecordRequest(
                CLIENT_RIDE_ID,
                startedAt,
                startedAt.plusMinutes(5),
                new RideRecordSummaryRequest(1200, 300),
                List.of(
                        point(1, "37.5010000", "127.0010000", startedAt),
                        point(2, "37.5020000", "127.0020000", startedAt.plusMinutes(2)),
                        point(3, "37.5030000", "127.0030000", startedAt.plusMinutes(5))
                )
        );
    }

    private RideRecordPointRequest point(
            int pointOrder,
            String latitude,
            String longitude,
            OffsetDateTime capturedAt
    ) {
        return new RideRecordPointRequest(
                pointOrder,
                new BigDecimal(latitude),
                new BigDecimal(longitude),
                capturedAt,
                new BigDecimal("5.0"),
                new BigDecimal("4.0"),
                null,
                null,
                null,
                null
        );
    }

    private int count(String sql, Object... arguments) {
        Integer result = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
        return result == null ? 0 : result;
    }

    private String fixture(String name) throws IOException {
        String path = "/fixtures/gpx/" + name;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
