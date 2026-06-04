package com.bikeprojectminji.bikeback.ride.policy.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.ride.policy.dto.RideLocationRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RouteProjectionIndexTest {

    @Test
    @DisplayName("잘못된 이전 segment 힌트가 있어도 full-scan fallback으로 올바른 최근접 선분을 찾는다")
    void findNearestFallsBackWhenPreferredWindowMisses() {
        Long courseId = 1L;
        RouteProjectionIndex index = new RouteProjectionIndex(List.of(
                routePoint(courseId, 1, 37.5665, 126.9780),
                routePoint(courseId, 2, offsetLatitudeMeters(37.5665, 300), 126.9780),
                routePoint(courseId, 3, offsetLatitudeMeters(37.5665, 300), offsetLongitudeMeters(offsetLatitudeMeters(37.5665, 300), 126.9780, 300)),
                routePoint(courseId, 4, 37.5665, offsetLongitudeMeters(37.5665, 126.9780, 300)),
                routePoint(courseId, 5, 37.5665, 126.9780)
        ));

        RouteProjectionIndex.RouteProjectionMatch nearest = index.findNearest(
                tracePoint(offsetLatitudeMeters(37.5665, 150), offsetLongitudeMeters(offsetLatitudeMeters(37.5665, 150), 126.9780, 3)),
                3
        );

        assertThat(nearest.segmentIndex()).isEqualTo(0);
        assertThat(nearest.distanceToRouteM()).isLessThan(5d);
    }

    @Test
    @DisplayName("현재 위치를 경로 누적거리와 잔여거리로 변환한다")
    void progressAtReturnsDistanceRemainingAndNearestSegment() {
        Long courseId = 1L;
        RouteProjectionIndex index = new RouteProjectionIndex(List.of(
                routePoint(courseId, 1, 37.5665, 126.9780),
                routePoint(courseId, 2, offsetLatitudeMeters(37.5665, 500), 126.9780),
                routePoint(courseId, 3, offsetLatitudeMeters(37.5665, 1_000), 126.9780)
        ));

        RouteProjectionIndex.RouteProgress progress = index.progressAt(
                tracePoint(offsetLatitudeMeters(37.5665, 400), 126.9780),
                null
        );

        assertThat(progress.distanceAlongRouteM()).isBetween(395, 405);
        assertThat(progress.remainingDistanceM()).isBetween(595, 605);
        assertThat(progress.progressPercent()).isEqualTo(40);
        assertThat(progress.nearestSegmentIndex()).isEqualTo(0);
    }

    @Test
    @DisplayName("폐회로 종점 좌표는 이전 segment 힌트가 있으면 마지막 진행률로 계산한다")
    void progressAtUsesPreviousSegmentHintForLoopFinishPoint() {
        Long courseId = 1L;
        RouteProjectionIndex index = new RouteProjectionIndex(List.of(
                routePoint(courseId, 1, 37.5665, 126.9780),
                routePoint(courseId, 2, offsetLatitudeMeters(37.5665, 300), 126.9780),
                routePoint(courseId, 3, offsetLatitudeMeters(37.5665, 300), offsetLongitudeMeters(offsetLatitudeMeters(37.5665, 300), 126.9780, 300)),
                routePoint(courseId, 4, 37.5665, offsetLongitudeMeters(37.5665, 126.9780, 300)),
                routePoint(courseId, 5, 37.5665, 126.9780)
        ));

        RouteProjectionIndex.RouteProgress progress = index.progressAt(
                tracePoint(37.5665, 126.9780),
                2
        );

        assertThat(progress.progressPercent()).isEqualTo(100);
        assertThat(progress.remainingDistanceM()).isEqualTo(0);
        assertThat(progress.nearestSegmentIndex()).isEqualTo(3);
    }

    private RideLocationRequest tracePoint(double lat, double lon) {
        return new RideLocationRequest(
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon),
                BigDecimal.valueOf(10.0),
                OffsetDateTime.parse("2026-03-29T10:15:19+09:00")
        );
    }

    private CourseRoutePointEntity routePoint(Long courseId, int order, double lat, double lon) {
        return new CourseRoutePointEntity(courseId, order, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon));
    }

    private double offsetLatitudeMeters(double latitude, int meters) {
        return latitude + (meters / 111_320d);
    }

    private double offsetLongitudeMeters(double latitude, double longitude, int meters) {
        return longitude + (meters / (111_320d * Math.cos(Math.toRadians(latitude))));
    }
}
