package com.bikeprojectminji.bikeback.service.ridepolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.dto.ridepolicy.RideLocationRequest;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationRequest;
import com.bikeprojectminji.bikeback.dto.ridepolicy.RidePolicyEvaluationResponse;
import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import com.bikeprojectminji.bikeback.entity.course.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.repository.course.CourseRepository;
import com.bikeprojectminji.bikeback.repository.course.CourseRoutePointRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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

    private RidePolicyService ridePolicyService;

    @BeforeEach
    void setUp() {
        ridePolicyService = new RidePolicyService(
                courseRepository,
                courseRoutePointRepository,
                Clock.fixed(Instant.parse("2026-03-29T01:15:30Z"), ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("시작점 150m 이내면 PRE_START에서 시작 가능 판정을 응답한다")
    void evaluateReturnsEligibleAtPreStart() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(
                routePoint(courseId, 1, 37.5660, 126.9770),
                routePoint(courseId, 2, 37.5670, 126.9790)
        ));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 18.5, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("ELIGIBLE");
        assertThat(response.offRoute().reasonCode()).isEqualTo("NOT_ACTIVE_YET");
        assertThat(response.overallState()).isEqualTo("PRE_START_ELIGIBLE");
    }

    @Test
    @DisplayName("시작점에서 멀어도 경로 선분 150m 이내면 PRE_START에서 시작 가능 판정을 응답한다")
    void evaluateReturnsEligibleWhenNearRouteSegment() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5900, 126.9900)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(
                routePoint(courseId, 1, 37.5660, 126.9770),
                routePoint(courseId, 2, 37.5670, 126.9790)
        ));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("PRE_START", 37.5665, 126.9780, 18.5, "2026-03-29T10:15:19+09:00"));

        assertThat(response.startGate().status()).isEqualTo("ELIGIBLE");
        assertThat(response.overallState()).isEqualTo("PRE_START_ELIGIBLE");
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
    @DisplayName("ACTIVE에서 경로 100m 이상 벗어나면 WARNING을 응답한다")
    void evaluateReturnsWarningWhenOffRoute() {
        Long courseId = 1L;
        given(courseRepository.findById(courseId)).willReturn(Optional.of(course(courseId, 37.5665, 126.9780)));
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId)).willReturn(List.of(routePoint(courseId, 1, 37.5665, 126.9780)));

        RidePolicyEvaluationResponse response = ridePolicyService.evaluate(courseId, request("ACTIVE", 37.5800, 127.0200, 18.5, "2026-03-29T10:15:19+09:00"));

        assertThat(response.offRoute().status()).isEqualTo("WARNING");
        assertThat(response.offRoute().reasonCode()).isEqualTo("OFF_ROUTE_THRESHOLD_EXCEEDED");
        assertThat(response.overallState()).isEqualTo("ACTIVE_WARNING");
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
                )
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
}
