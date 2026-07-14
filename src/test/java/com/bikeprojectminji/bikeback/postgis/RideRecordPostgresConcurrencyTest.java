package com.bikeprojectminji.bikeback.postgis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.airoute.session.AiRouteGenerationSessionService;
import com.bikeprojectminji.bikeback.airoute.session.AiRoutePromotedCourseResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionCreateRequest;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.PromoteAiRouteCandidateRequest;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.ImportGpxCourseRequest;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import com.bikeprojectminji.bikeback.course.repository.CourseRouteGeometryRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.course.service.CourseQueryService;
import com.bikeprojectminji.bikeback.location.service.RecentLocationCacheStore;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordResponse;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.ride.service.RideRecordService;
import com.bikeprojectminji.bikeback.ride.service.RideRecordDeletionService;
import java.math.BigDecimal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Tag("postgis")
@Testcontainers
@ActiveProfiles("test")
@AutoConfigureMockMvc
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
    private static boolean concurrencyPassed;
    private static boolean gpxImportPassed;
    private static boolean invalidGpxRejectedWithoutRows;
    private static boolean gpxMidWriteRollbackPassed;
    private static boolean ownershipPassed;
    private static boolean aiCandidateConcurrentPromotePassed;
    private static boolean aiCandidateRollbackPassed;
    private static boolean ridePolicyApiPostgisPassed;

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

    @AfterAll
    static void writeEvidence() throws Exception {
        Path output = Path.of("build", "public-evidence", "postgres-application-contract.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), new ApplicationContractEvidence(
                "postgres-application-contract-v1",
                System.getenv().getOrDefault("GIT_COMMIT", "working-tree"),
                Instant.now().toString(),
                "postgis/postgis:16-3.4; Redis lock forced unavailable",
                "./gradlew postgisTest --tests '*RideRecordPostgresConcurrencyTest'",
                applicationContractPassed() ? "PASS" : "FAIL",
                concurrencyPassed ? CONCURRENT_REQUESTS : 0,
                concurrencyPassed ? 1 : 0,
                concurrencyPassed ? 3 : 0,
                concurrencyPassed ? 1 : 0,
                gpxImportPassed,
                invalidGpxRejectedWithoutRows,
                gpxMidWriteRollbackPassed,
                ownershipPassed,
                aiCandidateConcurrentPromotePassed,
                aiCandidateRollbackPassed,
                ridePolicyApiPostgisPassed,
                "synthetic fixtures only; no real rider coordinates or credentials"
        ));
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
    private AiRouteGenerationSessionService aiRouteGenerationSessionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private RecentLocationCacheStore recentLocationCacheStore;

    @MockitoBean
    private StringRedisTemplate stringRedisTemplate;

    @MockitoBean
    private AiRoutePlannerService aiRoutePlannerService;

    @MockitoSpyBean
    private CourseRouteGeometryRepository courseRouteGeometryRepository;

    private UserEntity owner;

    @BeforeEach
    void setUpSyntheticOwnerAndRedisFailure() {
        jdbcTemplate.update("delete from ride_finalization_jobs");
        jdbcTemplate.update("delete from ride_record_points");
        jdbcTemplate.update("delete from ride_records");
        jdbcTemplate.update("delete from course_route_points where course_id in (select id from courses where owner_user_id in (select id from users where external_id = ?))", SUBJECT);
        jdbcTemplate.update("delete from courses where owner_user_id in (select id from users where external_id = ?)", SUBJECT);
        jdbcTemplate.update("delete from ai_route_candidates where session_id in (select id from ai_route_generation_sessions where owner_user_id in (select id from users where external_id = ?))", SUBJECT);
        jdbcTemplate.update("delete from ai_route_generation_sessions where owner_user_id in (select id from users where external_id = ?)", SUBJECT);
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
            concurrencyPassed = true;
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
        gpxImportPassed = true;
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
        invalidGpxRejectedWithoutRows = true;
    }

    @Test
    @DisplayName("GPX course와 points 저장 뒤 geometry 갱신 실패가 발생하면 전체 저장을 롤백한다")
    void rollsBackGpxWhenGeometryRefreshFails() throws IOException {
        String title = "synthetic-gpx-mid-write-failure";
        doThrow(new IllegalStateException("synthetic geometry refresh failure"))
                .when(courseRouteGeometryRepository).refreshRouteLine(anyLong());

        try {
            assertThatThrownBy(() -> courseService.importGpxCourse(SUBJECT, new ImportGpxCourseRequest(
                    title,
                    "non-user fixture",
                    "PRIVATE",
                    fixture("synthetic-normal.gpx")
            )))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("synthetic geometry refresh failure");
        } finally {
            reset(courseRouteGeometryRepository);
        }

        assertThat(count("select count(*) from courses where title = ? and owner_user_id = ?", title, owner.getId()))
                .isZero();
        assertThat(count("""
                select count(*) from course_route_points p
                join courses c on c.id = p.course_id
                where c.title = ? and c.owner_user_id = ?
                """, title, owner.getId())).isZero();
        gpxMidWriteRollbackPassed = true;
    }

    @Test
    @DisplayName("같은 AI 후보 동시 승격은 Course 한 건과 route point 한 묶음으로 수렴한다")
    void convergesConcurrentAiCandidatePromotion() throws Exception {
        AiRouteGenerationSessionResponse session = createAiRouteSession();
        Long candidateId = session.candidates().get(0).candidateId();
        PromoteAiRouteCandidateRequest request = promoteRequest("synthetic-concurrent-promote");
        CyclicBarrier startBarrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            List<Callable<Object>> calls = List.of(
                    () -> promoteAfterBarrier(session, candidateId, request, startBarrier),
                    () -> promoteAfterBarrier(session, candidateId, request, startBarrier)
            );
            List<Future<Object>> futures = executor.invokeAll(calls, 20, TimeUnit.SECONDS);
            int successCount = 0;
            int failureCount = 0;
            for (Future<Object> future : futures) {
                assertThat(future.isCancelled()).isFalse();
                try {
                    assertThat(future.get(5, TimeUnit.SECONDS)).isInstanceOf(AiRoutePromotedCourseResponse.class);
                    successCount++;
                } catch (ExecutionException expectedConcurrentLoser) {
                    failureCount++;
                }
            }

            assertThat(successCount).isEqualTo(1);
            assertThat(failureCount).isEqualTo(1);
            assertThat(count("select count(*) from courses where source_ai_route_candidate_id = ?", candidateId))
                    .isEqualTo(1);
            assertThat(count("""
                    select count(*) from course_route_points p
                    join courses c on c.id = p.course_id
                    where c.source_ai_route_candidate_id = ?
                    """, candidateId)).isEqualTo(2);
            assertThat(count("select count(*) from ai_route_candidates where id = ? and promoted_course_id is not null", candidateId))
                    .isEqualTo(1);
            aiCandidateConcurrentPromotePassed = true;
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("AI 후보 승격 중 geometry 갱신 실패가 발생하면 Course와 route points를 남기지 않는다")
    void rollsBackAiCandidatePromotionWhenGeometryRefreshFails() {
        AiRouteGenerationSessionResponse session = createAiRouteSession();
        Long candidateId = session.candidates().get(0).candidateId();
        doThrow(new IllegalStateException("synthetic promote geometry failure"))
                .when(courseRouteGeometryRepository).refreshRouteLine(anyLong());

        try {
            assertThatThrownBy(() -> aiRouteGenerationSessionService.promoteCandidate(
                    SUBJECT,
                    session.sessionId(),
                    candidateId,
                    promoteRequest("synthetic-rollback-promote")
            ))
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("synthetic promote geometry failure");
        } finally {
            reset(courseRouteGeometryRepository);
        }

        assertThat(count("select count(*) from courses where source_ai_route_candidate_id = ?", candidateId)).isZero();
        assertThat(count("select count(*) from ai_route_candidates where id = ? and promoted_course_id is not null", candidateId))
                .isZero();
        aiCandidateRollbackPassed = true;
    }

    @Test
    @DisplayName("대표 ride-policy HTTP 요청은 실제 PostGIS route points를 읽어 응답한다")
    void evaluatesRidePolicyThroughHttpAndPostgis() throws Exception {
        CourseWriteResponse course = courseService.importGpxCourse(SUBJECT, new ImportGpxCourseRequest(
                "synthetic-http-replay",
                "non-user fixture",
                "PUBLIC",
                fixture("synthetic-normal.gpx")
        ));
        OffsetDateTime now = OffsetDateTime.now();
        RidePolicyEvaluationRequest request = new RidePolicyEvaluationRequest(
                "ACTIVE",
                new RideLocationRequest(new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), new BigDecimal("5"), now),
                List.of(new RideLocationRequest(
                        new BigDecimal("37.5010000"), new BigDecimal("127.0010000"), new BigDecimal("5"), now.minusSeconds(1)
                ))
        );

        mockMvc.perform(post("/api/v1/courses/{courseId}/ride-policy/evaluate", course.courseId())
                        .contentType("application/json")
                        .content(new ObjectMapper().findAndRegisterModules().writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phase").value("ACTIVE"))
                .andExpect(jsonPath("$.data.offRoute.status").value("ON_ROUTE"))
                .andExpect(jsonPath("$.data.progress.nearestSegmentIndex").isNumber());
        ridePolicyApiPostgisPassed = true;
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
        ownershipPassed = true;
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

    private AiRouteGenerationSessionResponse createAiRouteSession() {
        given(aiRoutePlannerService.plan(org.mockito.ArgumentMatchers.eq(SUBJECT), org.mockito.ArgumentMatchers.any(AiRoutePlanRequest.class)))
                .willReturn(new AiRoutePlanResponse(
                        "synthetic-plan",
                        "READY",
                        "synthetic candidate",
                        "HIGH",
                        null,
                        null,
                        List.of(
                                new AiRoutePointResponse(new BigDecimal("37.5000000"), new BigDecimal("127.0000000"), "synthetic start"),
                                new AiRoutePointResponse(new BigDecimal("37.5100000"), new BigDecimal("127.0100000"), "synthetic end")
                        ),
                        List.of(),
                        List.of(),
                        80,
                        null,
                        null,
                        List.of(),
                        true
                ));
        return aiRouteGenerationSessionService.createSession(SUBJECT, new AiRouteGenerationSessionCreateRequest(
                new BigDecimal("37.5000000"),
                new BigDecimal("127.0000000"),
                new BigDecimal("37.5100000"),
                new BigDecimal("127.0100000"),
                "synthetic destination",
                "BALANCED",
                "BALANCED_ELEVATION",
                "synthetic preference"
        ));
    }

    private Object promoteAfterBarrier(
            AiRouteGenerationSessionResponse session,
            Long candidateId,
            PromoteAiRouteCandidateRequest request,
            CyclicBarrier startBarrier
    ) throws Exception {
        startBarrier.await(10, TimeUnit.SECONDS);
        return aiRouteGenerationSessionService.promoteCandidate(SUBJECT, session.sessionId(), candidateId, request);
    }

    private PromoteAiRouteCandidateRequest promoteRequest(String name) {
        return new PromoteAiRouteCandidateRequest(name, "non-user fixture", "PRIVATE");
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

    private static boolean applicationContractPassed() {
        return concurrencyPassed
                && gpxImportPassed
                && invalidGpxRejectedWithoutRows
                && gpxMidWriteRollbackPassed
                && ownershipPassed
                && aiCandidateConcurrentPromotePassed
                && aiCandidateRollbackPassed
                && ridePolicyApiPostgisPassed;
    }

    private String fixture(String name) throws IOException {
        String path = "/fixtures/gpx/" + name;
        try (InputStream input = getClass().getResourceAsStream(path)) {
            assertThat(input).as(path).isNotNull();
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record ApplicationContractEvidence(
            String testId,
            String commit,
            String executedAt,
            String environment,
            String command,
            String result,
            int concurrentRequests,
            int rideRecordRows,
            int routePointRows,
            int finalizationJobRows,
            boolean gpxImportWithGeometryPassed,
            boolean invalidGpxRejectedWithoutRows,
            boolean gpxMidWriteRollbackPassed,
            boolean crossOwnerAccessDenied,
            boolean aiCandidateConcurrentPromotePassed,
            boolean aiCandidateRollbackPassed,
            boolean ridePolicyApiPostgisPassed,
            String limitation
    ) {
    }
}
