package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CourseRouteSnapshotServiceTest {

    @Mock
    private CourseRoutePointRepository courseRoutePointRepository;

    @Test
    @DisplayName("같은 코스 route snapshot을 반복 조회하면 repository를 한 번만 읽는다")
    void getCachesRouteSnapshot() {
        BikeMetricsRecorder metricsRecorder = new BikeMetricsRecorder(new SimpleMeterRegistry());
        CourseRouteSnapshotService snapshotService = new CourseRouteSnapshotService(
                courseRoutePointRepository,
                metricsRecorder,
                Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
                true,
                600
        );
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(7L)).willReturn(List.of(
                routePoint(7L, 1, 37.5665, 126.9780),
                routePoint(7L, 2, 37.5671, 126.9792)
        ));

        CourseRouteSnapshot first = snapshotService.get(7L, "route_points");
        CourseRouteSnapshot second = snapshotService.get(7L, "route_points");

        assertThat(first.responsePoints()).hasSize(2);
        assertThat(second.routeProjectionIndex().segments()).hasSize(1);
        verify(courseRoutePointRepository, times(1)).findByCourseIdOrderByPointOrderAsc(7L);
    }

    @Test
    @DisplayName("route snapshot을 evict하면 다음 조회에서 repository를 다시 읽는다")
    void evictForcesReload() {
        BikeMetricsRecorder metricsRecorder = new BikeMetricsRecorder(new SimpleMeterRegistry());
        CourseRouteSnapshotService snapshotService = new CourseRouteSnapshotService(
                courseRoutePointRepository,
                metricsRecorder,
                Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
                true,
                600
        );
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(7L)).willReturn(List.of(
                routePoint(7L, 1, 37.5665, 126.9780),
                routePoint(7L, 2, 37.5671, 126.9792)
        ));

        snapshotService.get(7L, "route_points");
        snapshotService.evict(7L, "route_points_updated");
        snapshotService.get(7L, "route_points");

        verify(courseRoutePointRepository, times(2)).findByCourseIdOrderByPointOrderAsc(7L);
    }

    @Test
    @DisplayName("서로 다른 코스 route snapshot cache miss는 전역 lock 없이 병렬로 적재한다")
    void getLoadsDifferentCourseMissesInParallel() throws Exception {
        BikeMetricsRecorder metricsRecorder = new BikeMetricsRecorder(new SimpleMeterRegistry());
        CourseRouteSnapshotService snapshotService = new CourseRouteSnapshotService(
                courseRoutePointRepository,
                metricsRecorder,
                Clock.fixed(Instant.parse("2026-05-04T00:00:00Z"), ZoneOffset.UTC),
                true,
                600
        );
        CountDownLatch concurrentLoads = new CountDownLatch(2);
        given(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(org.mockito.ArgumentMatchers.anyLong()))
                .willAnswer(invocation -> {
                    Long courseId = invocation.getArgument(0);
                    concurrentLoads.countDown();
                    assertThat(concurrentLoads.await(1, TimeUnit.SECONDS))
                            .as("서로 다른 courseId miss는 동시에 repository 적재에 진입해야 한다")
                            .isTrue();
                    return List.of(
                            routePoint(courseId, 1, 37.5665, 126.9780),
                            routePoint(courseId, 2, 37.5671, 126.9792)
                    );
                });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<CourseRouteSnapshot> first = executor.submit(() -> snapshotService.get(7L, "route_points"));
            Future<CourseRouteSnapshot> second = executor.submit(() -> snapshotService.get(8L, "route_points"));

            assertThat(first.get(2, TimeUnit.SECONDS).courseId()).isEqualTo(7L);
            assertThat(second.get(2, TimeUnit.SECONDS).courseId()).isEqualTo(8L);
        } finally {
            executor.shutdownNow();
        }
    }

    private CourseRoutePointEntity routePoint(Long courseId, int order, double lat, double lon) {
        return new CourseRoutePointEntity(courseId, order, BigDecimal.valueOf(lat), BigDecimal.valueOf(lon));
    }
}
