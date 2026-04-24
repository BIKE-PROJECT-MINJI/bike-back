package com.bikeprojectminji.bikeback.ride.policy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.ride.policy.dto.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RidePolicyServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CourseRoutePointRepository courseRoutePointRepository;

    @Mock
    private BikeMetricsRecorder bikeMetricsRecorder;

    private RidePolicyService ridePolicyService;

    @BeforeEach
    void setUp() {
        ridePolicyService = new RidePolicyService(
                courseRepository,
                courseRoutePointRepository,
                bikeMetricsRecorder,
                Clock.fixed(Instant.parse("2026-03-29T01:15:30Z"), ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("시작점 50m 이내면 PRE_START에서 시작 가능 판정을 응답한다")
    void evaluateReturnsEligibleAtPreStart() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(
                routePoint(courseId, 1, 37.5660, 126.9770),
                routePoint(courseId, 2, 37.5670, 126.9790)
        ));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 18.5, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("ELIGIBLE");
        assertThat(response.startGate().reasonCode()).isEqualTo("WITHIN_START_POINT_THRESHOLD");
        assertThat(response.startGate().distanceM()).isNotNull();
        assertThat(response.startGate().thresholdM()).isEqualTo(50);
        assertThat(response.offRoute().reasonCode()).isEqualTo("NOT_ACTIVE_YET");
        assertThat(response.overallState()).isEqualTo("PRE_START_ELIGIBLE");
    }

    @Test
    @DisplayName("시작점에서 멀면 경로에 가까워도 PRE_START를 차단한다")
    void evaluateBlocksWhenOnlyNearRouteSegment() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5900, 126.9900)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(
                routePoint(courseId, 1, 37.5660, 126.9770),
                routePoint(courseId, 2, 37.5670, 126.9790)
        ));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 18.5, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("BLOCKED");
        assertThat(response.startGate().reasonCode()).isEqualTo("START_POINT_THRESHOLD_EXCEEDED");
        assertThat(response.startGate().distanceM()).isGreaterThan(50);
        assertThat(response.startGate().thresholdM()).isEqualTo(50);
        assertThat(response.overallState()).isEqualTo("PRE_START_BLOCKED");
    }

    @Test
    @DisplayName("정확도 120m 초과면 PRE_START에서 불확정 상태를 응답한다")
    void evaluateReturnsUndeterminedWhenAccuracyIsLow() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 125.0, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("UNDETERMINED");
        assertThat(response.startGate().reasonCode()).isEqualTo("LOCATION_LOW_ACCURACY");
        assertThat(response.defaultMessage()).contains("위치 정확도");
    }

    @Test
    @DisplayName("ACTIVE에서 경로 50m를 15초 이상 벗어나면 WARNING을 응답한다")
    void evaluateReturnsWarningWhenOffRouteCandidatePersistsForFifteenSeconds() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        offsetLongitudeMeters(37.5665, 126.9780, 51),
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of(
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:04+09:00")
                        )
                )
        );

        assertThat(response.offRoute().status()).isEqualTo("WARNING");
        assertThat(response.offRoute().reasonCode()).isEqualTo("OFF_ROUTE_WARNING_ACTIVE");
        assertThat(response.offRoute().distanceM()).isEqualTo(51);
        assertThat(response.offRoute().candidateThresholdM()).isEqualTo(50);
        assertThat(response.offRoute().recoveryThresholdM()).isEqualTo(30);
        assertThat(response.offRoute().durationSec()).isEqualTo(15);
        assertThat(response.overallState()).isEqualTo("ACTIVE_WARNING");
    }

    @Test
    @DisplayName("ACTIVE에서 경로 49m 이내면 off-route 후보를 시작하지 않는다")
    void evaluateKeepsOnRouteWithinFortyNineMeters() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        offsetLongitudeMeters(37.5665, 126.9780, 49),
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of()
                )
        );

        assertThat(response.offRoute().status()).isEqualTo("ON_ROUTE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("WITHIN_ROUTE_THRESHOLD");
        assertThat(response.offRoute().distanceM()).isEqualTo(49);
        assertThat(response.offRoute().durationSec()).isEqualTo(0);
        assertThat(response.overallState()).isEqualTo("ACTIVE_ON_ROUTE");
    }

    @Test
    @DisplayName("ACTIVE에서 경로 51m 이탈이 15초 미만이면 후보 상태만 유지한다")
    void evaluateReturnsCandidateBeforeWarningThreshold() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        offsetLongitudeMeters(37.5665, 126.9780, 51),
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of(
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:09+09:00")
                        )
                )
        );

        assertThat(response.offRoute().status()).isEqualTo("CANDIDATE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("OFF_ROUTE_CANDIDATE_ACTIVE");
        assertThat(response.offRoute().distanceM()).isEqualTo(51);
        assertThat(response.offRoute().durationSec()).isEqualTo(10);
        assertThat(response.overallState()).isEqualTo("ACTIVE_OFF_ROUTE_CANDIDATE");
    }

    @Test
    @DisplayName("ACTIVE에서 warning 이후 31m면 recovery 대기 상태를 유지한다")
    void evaluateKeepsWarningUntilRecoveryThresholdIsMet() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        offsetLongitudeMeters(37.5665, 126.9780, 31),
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of(
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:00+09:00"),
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:04+09:00")
                        )
                )
        );

        assertThat(response.offRoute().status()).isEqualTo("WARNING");
        assertThat(response.offRoute().reasonCode()).isEqualTo("OFF_ROUTE_RECOVERY_PENDING");
        assertThat(response.offRoute().distanceM()).isEqualTo(31);
        assertThat(response.offRoute().recoveryThresholdM()).isEqualTo(30);
        assertThat(response.overallState()).isEqualTo("ACTIVE_WARNING");
    }

    @Test
    @DisplayName("ACTIVE에서 warning 이후 29m로 복귀하면 on-route로 회복한다")
    void evaluateRecoversWhenWithinTwentyNineMeters() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        offsetLongitudeMeters(37.5665, 126.9780, 29),
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of(
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:00+09:00"),
                                tracePoint(37.5665, offsetLongitudeMeters(37.5665, 126.9780, 51), 18.5, "2026-03-29T10:15:04+09:00")
                        )
                )
        );

        assertThat(response.offRoute().status()).isEqualTo("ON_ROUTE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("RECOVERED_WITHIN_THRESHOLD");
        assertThat(response.offRoute().distanceM()).isEqualTo(29);
        assertThat(response.offRoute().durationSec()).isEqualTo(0);
        assertThat(response.overallState()).isEqualTo("ACTIVE_ON_ROUTE");
    }

    @Test
    @DisplayName("ACTIVE non-loop 코스는 coverage 79면 종점 근처여도 완주 후보가 될 수 없다")
    void evaluateBlocksNonLoopCompletionWhenCoverageIsSeventyNinePercent() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        offsetLatitudeMeters(37.5665, 790),
                        126.9780,
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        straightTrace(37.5665, 126.9780, 0, 790, 10)
                )
        );

        assertThat(response.completion().status()).isEqualTo("IN_PROGRESS");
        assertThat(response.completion().reasonCode()).isEqualTo("COVERAGE_BELOW_THRESHOLD");
        assertThat(response.completion().coveragePercent()).isEqualTo(79);
        assertThat(response.completion().coverageThresholdPercent()).isEqualTo(80);
    }

    @Test
    @DisplayName("ACTIVE non-loop 코스는 coverage 80이면 coverage 부족 사유를 넘긴다")
    void evaluateAcceptsCoverageAtEightyPercentBoundary() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        offsetLatitudeMeters(37.5665, 800),
                        126.9780,
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        straightTrace(37.5665, 126.9780, 0, 800, 10)
                )
        );

        assertThat(response.completion().coveragePercent()).isEqualTo(80);
        assertThat(response.completion().reasonCode()).isEqualTo("AWAITING_DESTINATION");
        assertThat(response.completion().status()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("ACTIVE non-loop 코스는 coverage 80 이상이고 종점 150m 이내면 완주 후보를 응답한다")
    void evaluateReturnsCompletionReadyForNonLoopCourse() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(straightRoute(courseId, 37.5665, 126.9780, 1_000));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        offsetLatitudeMeters(37.5665, 860),
                        126.9780,
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        straightTrace(37.5665, 126.9780, 0, 860, 10)
                )
        );

        assertThat(response.completion().status()).isEqualTo("ELIGIBLE");
        assertThat(response.completion().reasonCode()).isEqualTo("NON_LOOP_COMPLETION_READY");
        assertThat(response.completion().coveragePercent()).isGreaterThanOrEqualTo(80);
        assertThat(response.completion().loopCourse()).isFalse();
        assertThat(response.overallState()).isEqualTo("ACTIVE_COMPLETION_READY");
    }

    @Test
    @DisplayName("ACTIVE loop 코스는 시작점으로 돌아와도 coverage가 부족하면 완주 후보가 될 수 없다")
    void evaluateBlocksLoopCompletionWhenCoverageIsInsufficient() {
        Long courseId = 1L;
        List<CourseRoutePointEntity> loopRoute = loopRoute(courseId, 37.5665, 126.9780, 300);
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(loopRoute);

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(
                courseId,
                activeRequest(
                        37.5665,
                        126.9780,
                        18.5,
                        "2026-03-29T10:15:19+09:00",
                        List.of(
                                tracePoint(37.5665, 126.9780, 18.5, "2026-03-29T10:00:00+09:00"),
                                tracePoint(offsetLatitudeMeters(37.5665, 300), 126.9780, 18.5, "2026-03-29T10:05:00+09:00"),
                                tracePoint(offsetLatitudeMeters(37.5665, 300), offsetLongitudeMeters(offsetLatitudeMeters(37.5665, 300), 126.9780, 300), 18.5, "2026-03-29T10:10:00+09:00"),
                                tracePoint(37.5665, 126.9780, 18.5, "2026-03-29T10:15:00+09:00")
                        )
                )
        );

        assertThat(response.completion().status()).isEqualTo("IN_PROGRESS");
        assertThat(response.completion().reasonCode()).isEqualTo("COVERAGE_BELOW_THRESHOLD");
        assertThat(response.completion().loopCourse()).isTrue();
        assertThat(response.completion().leftStartZone()).isTrue();
        assertThat(response.completion().coveragePercent()).isLessThan(80);
    }

    @Test
    @DisplayName("ACTIVE에서는 정확도 50m 초과면 판단 보류를 응답한다")
    void evaluateReturnsUndeterminedWhenActiveAccuracyExceedsStrictThreshold() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("ACTIVE", 37.5665, 126.9780, 55.0, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("UNDETERMINED");
        assertThat(response.offRoute().status()).isEqualTo("UNDETERMINED");
        assertThat(response.startGate().reasonCode()).isEqualTo("LOCATION_LOW_ACCURACY");
        assertThat(response.offRoute().reasonCode()).isEqualTo("LOCATION_LOW_ACCURACY");
        assertThat(response.defaultMessage()).isEqualTo("현재 위치 정확도가 낮아 주행 정책을 판단하기 어렵습니다.");
    }

    @Test
    @DisplayName("ACTIVE에서는 stale 15초 초과 위치면 판단 보류를 응답한다")
    void evaluateReturnsUndeterminedWhenActiveLocationExceedsStrictStaleThreshold() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("ACTIVE", 37.5665, 126.9780, 10.0, "2026-03-29T10:15:10+09:00"));

        assertThat(response.startGate().status()).isEqualTo("UNDETERMINED");
        assertThat(response.offRoute().status()).isEqualTo("UNDETERMINED");
        assertThat(response.startGate().reasonCode()).isEqualTo("LOCATION_STALE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("LOCATION_STALE");
        assertThat(response.defaultMessage()).isEqualTo("위치 정보가 오래되어 주행 정책을 판단하기 어렵습니다.");
    }

    @Test
    @DisplayName("PRE_START에서는 ACTIVE 전용 엄격 기준보다 완화된 정확도 기준을 유지한다")
    void evaluateKeepsLooserAccuracyThresholdAtPreStart() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 55.0, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("ELIGIBLE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("NOT_ACTIVE_YET");
        assertThat(response.overallState()).isEqualTo("PRE_START_ELIGIBLE");
    }

    @Test
    @DisplayName("ACTIVE에서 stale 60초 초과 위치면 판단 보류를 응답한다")
    void evaluateReturnsUndeterminedWhenLocationIsStale() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("ACTIVE", 37.5665, 126.9780, 10.0, "2026-03-29T10:14:00+09:00"));

        assertThat(response.offRoute().status()).isEqualTo("UNDETERMINED");
        assertThat(response.startGate().reasonCode()).isEqualTo("LOCATION_STALE");
    }

    @Test
    @DisplayName("코스가 없으면 NotFound를 던진다")
    void evaluateThrowsWhenCourseNotFound() {
        given(courseRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> ridePolicyService.evaluate(999L, request("PRE_START", 37.5665, 126.9780, 10.0, "2026-03-29T10:15:19+09:00")))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("코스를 찾을 수 없습니다.");
    }

    private RidePolicyEvaluationRequest request(String phase, double lat, double lon, double accuracyM, String capturedAt) {
        return new RidePolicyEvaluationRequest(
                phase,
                new RideLocationRequest(
                        BigDecimal.valueOf(lat),
                        BigDecimal.valueOf(lon),
                        BigDecimal.valueOf(accuracyM),
                        OffsetDateTime.parse(capturedAt)
                ),
                List.of()
        );
    }

    private RidePolicyEvaluationRequest activeRequest(
            double lat,
            double lon,
            double accuracyM,
            String capturedAt,
            List<RideLocationRequest> trace
    ) {
        return new RidePolicyEvaluationRequest(
                "ACTIVE",
                new RideLocationRequest(
                        BigDecimal.valueOf(lat),
                        BigDecimal.valueOf(lon),
                        BigDecimal.valueOf(accuracyM),
                        OffsetDateTime.parse(capturedAt)
                ),
                trace
        );
    }

    private RideLocationRequest tracePoint(double lat, double lon, double accuracyM, String capturedAt) {
        return new RideLocationRequest(
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon),
                BigDecimal.valueOf(accuracyM),
                OffsetDateTime.parse(capturedAt)
        );
    }

    private CourseEntity course(Long id, double lat, double lon) {
        CourseEntity entity = new CourseEntity(
                "테스트 코스",
                BigDecimal.valueOf(12.3),
                50,
                1,
                true,
                1,
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon)
        );
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }

    private CourseRoutePointEntity routePoint(Long courseId, int order, double lat, double lon) {
        return new CourseRoutePointEntity(courseId, order, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon));
    }

    private List<CourseRoutePointEntity> straightRoute(Long courseId, double startLat, double startLon, int meters) {
        List<CourseRoutePointEntity> points = new ArrayList<>();
        for (int meter = 0, order = 1; meter <= meters; meter += 100, order++) {
            points.add(routePoint(courseId, order, offsetLatitudeMeters(startLat, meter), startLon));
        }
        return points;
    }

    private List<RideLocationRequest> straightTrace(double startLat, double startLon, int fromMeters, int toMeters, int stepCount) {
        List<RideLocationRequest> trace = new ArrayList<>();
        int interval = Math.max(1, (toMeters - fromMeters) / stepCount);
        for (int meter = fromMeters; meter < toMeters; meter += interval) {
            trace.add(tracePoint(offsetLatitudeMeters(startLat, meter), startLon, 18.5, "2026-03-29T10:10:00+09:00"));
        }
        return trace;
    }

    private List<CourseRoutePointEntity> loopRoute(Long courseId, double startLat, double startLon, int sideMeters) {
        double northLat = offsetLatitudeMeters(startLat, sideMeters);
        double eastLon = offsetLongitudeMeters(startLat, startLon, sideMeters);
        return List.of(
                routePoint(courseId, 1, startLat, startLon),
                routePoint(courseId, 2, northLat, startLon),
                routePoint(courseId, 3, northLat, eastLon),
                routePoint(courseId, 4, startLat, eastLon),
                routePoint(courseId, 5, startLat, startLon)
        );
    }

    private double offsetLatitudeMeters(double latitude, int meters) {
        return latitude + (meters / 111_320d);
    }

    private double offsetLongitudeMeters(double latitude, double longitude, int meters) {
        return longitude + (meters / (111_320d * Math.cos(Math.toRadians(latitude))));
    }
}
