package com.bikeprojectminji.bikeback.ride.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.service.CourseRouteSnapshot;
import com.bikeprojectminji.bikeback.course.service.CourseRouteSnapshotService;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RidePolicyTraceReplayTest {

    private static final Long STRAIGHT_COURSE_ID = 9101L;
    private static final Long LOOP_COURSE_ID = 9102L;
    private static final double START_LAT = 37.5000;
    private static final double START_LON = 127.0000;
    private static final Instant FIXED_NOW = Instant.parse("2026-01-15T01:00:30Z");

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CourseRouteSnapshotService courseRouteSnapshotService;

    @Mock
    private AuthService authService;

    @Mock
    private BikeMetricsRecorder bikeMetricsRecorder;

    private RidePolicyService ridePolicyService;

    @BeforeEach
    void setUpSyntheticCourses() {
        ridePolicyService = new RidePolicyService(
                courseRepository,
                courseRouteSnapshotService,
                authService,
                bikeMetricsRecorder,
                Clock.fixed(FIXED_NOW, ZoneOffset.UTC)
        );

        List<CourseRoutePointEntity> straightRoute = straightRoute(STRAIGHT_COURSE_ID, 1_000);
        List<CourseRoutePointEntity> loopRoute = loopRoute(LOOP_COURSE_ID, 300);
        given(courseRepository.findById(STRAIGHT_COURSE_ID))
                .willReturn(Optional.of(course(STRAIGHT_COURSE_ID)));
        given(courseRepository.findById(LOOP_COURSE_ID))
                .willReturn(Optional.of(course(LOOP_COURSE_ID)));
        given(courseRouteSnapshotService.get(STRAIGHT_COURSE_ID, "ride_policy"))
                .willReturn(snapshot(STRAIGHT_COURSE_ID, straightRoute));
        given(courseRouteSnapshotService.get(LOOP_COURSE_ID, "ride_policy"))
                .willReturn(snapshot(LOOP_COURSE_ID, loopRoute));
    }

    @Test
    @DisplayName("RP-01부터 RP-09까지 synthetic trace 경계를 한 번에 재생한다")
    void replayAllNineSyntheticPolicyBoundaries() throws Exception {
        List<ReplayResult> results = new ArrayList<>();

        results.add(replay("RP-01", STRAIGHT_COURSE_ID, active(
                location(START_LAT, offsetLongitude(49), 10, second(-1)), List.of())));
        results.add(replay("RP-02", STRAIGHT_COURSE_ID, active(
                location(START_LAT, offsetLongitude(51), 10, second(-1)),
                List.of(location(START_LAT, offsetLongitude(51), 10, second(-11))))));
        results.add(replay("RP-03", STRAIGHT_COURSE_ID, active(
                location(START_LAT, offsetLongitude(51), 10, second(-1)),
                List.of(location(START_LAT, offsetLongitude(51), 10, second(-16))))));
        results.add(replay("RP-04", STRAIGHT_COURSE_ID, active(
                location(START_LAT, offsetLongitude(29), 10, second(-1)),
                List.of(
                        location(START_LAT, offsetLongitude(51), 10, second(-20)),
                        location(START_LAT, offsetLongitude(51), 10, second(-5))
                ))));
        results.add(replay("RP-05", STRAIGHT_COURSE_ID, active(
                location(START_LAT, START_LON, 51, second(-1)), List.of())));
        results.add(replay("RP-06", STRAIGHT_COURSE_ID, active(
                location(START_LAT, START_LON, 10, second(-16)), List.of())));
        results.add(replay("RP-07", STRAIGHT_COURSE_ID, active(
                location(offsetLatitude(790), START_LON, 10, second(-1)),
                straightTrace(0, 790, 10))));
        results.add(replay("RP-08", STRAIGHT_COURSE_ID, active(
                location(offsetLatitude(860), START_LON, 10, second(-1)),
                straightTrace(0, 860, 10))));
        results.add(replay("RP-09", LOOP_COURSE_ID, active(
                location(START_LAT, START_LON, 10, second(-1)),
                loopTrace())));

        assertOffRoute(results, "RP-01", "ON_ROUTE", "WITHIN_ROUTE_THRESHOLD", 49, 0);
        assertOffRoute(results, "RP-02", "CANDIDATE", "OFF_ROUTE_CANDIDATE_ACTIVE", 51, 10);
        assertOffRoute(results, "RP-03", "WARNING", "OFF_ROUTE_WARNING_ACTIVE", 51, 15);
        assertOffRoute(results, "RP-04", "ON_ROUTE", "RECOVERED_WITHIN_THRESHOLD", 29, 0);
        assertOffRoute(results, "RP-05", "UNDETERMINED", "LOCATION_LOW_ACCURACY", null, null);
        assertOffRoute(results, "RP-06", "UNDETERMINED", "LOCATION_STALE", null, null);
        assertCompletion(results, "RP-07", "IN_PROGRESS", "COVERAGE_BELOW_THRESHOLD", 79);
        assertCompletion(results, "RP-08", "ELIGIBLE", "NON_LOOP_COMPLETION_READY", 86);
        assertCompletion(results, "RP-09", "ELIGIBLE", "LOOP_COMPLETION_READY", 100);

        Path output = Path.of("build", "public-evidence", "route-policy-replay.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(output.toFile(), new ReplayEvidence(
                "route-policy-synthetic-replay-v1",
                System.getenv().getOrDefault("GIT_COMMIT", "working-tree"),
                Instant.now().toString(),
                "JVM unit test; fixed clock; synthetic route and trace",
                "./gradlew test --tests '*RidePolicyTraceReplayTest'",
                "PASS",
                "synthetic coordinates only; not a real ride accuracy claim",
                results.size(),
                results
        ));
        assertThat(results).hasSize(9);
    }

    private ReplayResult replay(String testId, Long courseId, RidePolicyEvaluationRequest request) {
        long startedNanos = System.nanoTime();
        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request);
        long latencyMicros = (System.nanoTime() - startedNanos) / 1_000;
        return new ReplayResult(
                testId,
                response.overallState(),
                response.offRoute().status(),
                response.offRoute().reasonCode(),
                response.offRoute().distanceM(),
                response.offRoute().durationSec(),
                response.completion().status(),
                response.completion().reasonCode(),
                response.completion().coveragePercent(),
                latencyMicros
        );
    }

    private void assertOffRoute(
            List<ReplayResult> results,
            String testId,
            String status,
            String reasonCode,
            Integer distanceM,
            Integer durationSec
    ) {
        ReplayResult result = result(results, testId);
        assertThat(result.offRouteStatus()).isEqualTo(status);
        assertThat(result.offRouteReasonCode()).isEqualTo(reasonCode);
        assertThat(result.distanceM()).isEqualTo(distanceM);
        assertThat(result.durationSec()).isEqualTo(durationSec);
    }

    private void assertCompletion(
            List<ReplayResult> results,
            String testId,
            String status,
            String reasonCode,
            int coveragePercent
    ) {
        ReplayResult result = result(results, testId);
        assertThat(result.completionStatus()).isEqualTo(status);
        assertThat(result.completionReasonCode()).isEqualTo(reasonCode);
        assertThat(result.coveragePercent()).isEqualTo(coveragePercent);
    }

    private ReplayResult result(List<ReplayResult> results, String testId) {
        return results.stream()
                .filter(result -> result.testId().equals(testId))
                .findFirst()
                .orElseThrow();
    }

    private RidePolicyEvaluationRequest active(RideLocationRequest location, List<RideLocationRequest> trace) {
        return new RidePolicyEvaluationRequest("ACTIVE", location, trace);
    }

    private RideLocationRequest location(double lat, double lon, double accuracyM, OffsetDateTime capturedAt) {
        return new RideLocationRequest(
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon),
                BigDecimal.valueOf(accuracyM),
                capturedAt
        );
    }

    private OffsetDateTime second(long offset) {
        return OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(offset), ZoneOffset.UTC);
    }

    private CourseEntity course(Long courseId) {
        CourseEntity course = new CourseEntity(
                "synthetic replay course",
                BigDecimal.ONE,
                5,
                Math.toIntExact(courseId),
                false,
                null,
                BigDecimal.valueOf(START_LAT),
                BigDecimal.valueOf(START_LON)
        );
        ReflectionTestUtils.setField(course, "id", courseId);
        return course;
    }

    private CourseRouteSnapshot snapshot(Long courseId, List<CourseRoutePointEntity> routePoints) {
        return new CourseRouteSnapshot(
                courseId,
                routePoints,
                routePoints.stream()
                        .map(point -> new CourseRoutePointResponse(
                                point.getPointOrder(),
                                point.getLatitude(),
                                point.getLongitude()
                        ))
                        .toList(),
                new RouteProjectionIndex(routePoints)
        );
    }

    private List<CourseRoutePointEntity> straightRoute(Long courseId, int meters) {
        List<CourseRoutePointEntity> points = new ArrayList<>();
        for (int meter = 0, order = 1; meter <= meters; meter += 100, order++) {
            points.add(routePoint(courseId, order, offsetLatitude(meter), START_LON));
        }
        return points;
    }

    private List<CourseRoutePointEntity> loopRoute(Long courseId, int sideMeters) {
        double northLat = offsetLatitude(sideMeters);
        double eastLon = offsetLongitude(sideMeters);
        return List.of(
                routePoint(courseId, 1, START_LAT, START_LON),
                routePoint(courseId, 2, northLat, START_LON),
                routePoint(courseId, 3, northLat, eastLon),
                routePoint(courseId, 4, START_LAT, eastLon),
                routePoint(courseId, 5, START_LAT, START_LON)
        );
    }

    private CourseRoutePointEntity routePoint(Long courseId, int order, double lat, double lon) {
        return new CourseRoutePointEntity(
                courseId,
                order,
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon)
        );
    }

    private List<RideLocationRequest> straightTrace(int fromMeters, int toMeters, int stepCount) {
        List<RideLocationRequest> trace = new ArrayList<>();
        int interval = Math.max(1, (toMeters - fromMeters) / stepCount);
        int index = 0;
        for (int meter = fromMeters; meter < toMeters; meter += interval) {
            trace.add(location(offsetLatitude(meter), START_LON, 10, second(-14 + index++)));
        }
        return trace;
    }

    private List<RideLocationRequest> loopTrace() {
        double northLat = offsetLatitude(300);
        double eastLon = offsetLongitude(300);
        return List.of(
                location(START_LAT, START_LON, 10, second(-20)),
                location(northLat, START_LON, 10, second(-15)),
                location(northLat, eastLon, 10, second(-10)),
                location(START_LAT, eastLon, 10, second(-5))
        );
    }

    private double offsetLatitude(int meters) {
        return START_LAT + (meters / 111_320d);
    }

    private double offsetLongitude(int meters) {
        return START_LON + (meters / (111_320d * Math.cos(Math.toRadians(START_LAT))));
    }

    private record ReplayEvidence(
            String testId,
            String commit,
            String executedAt,
            String environment,
            String command,
            String result,
            String fixtureNotice,
            int passedCases,
            List<ReplayResult> results
    ) {
    }

    private record ReplayResult(
            String testId,
            String overallState,
            String offRouteStatus,
            String offRouteReasonCode,
            Integer distanceM,
            Integer durationSec,
            String completionStatus,
            String completionReasonCode,
            Integer coveragePercent,
            long latencyMicros
    ) {
    }
}
