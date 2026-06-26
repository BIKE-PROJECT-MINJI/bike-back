package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import com.bikeprojectminji.bikeback.ride.policy.service.RouteProjectionIndex;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class CourseRouteSnapshotService {

    private final CourseRoutePointRepository courseRoutePointRepository;
    private final BikeMetricsRecorder bikeMetricsRecorder;
    private final Clock clock;
    private final boolean enabled;
    private final Duration ttl;
    private final ConcurrentHashMap<Long, CachedCourseRouteSnapshot> cache = new ConcurrentHashMap<>();

    public CourseRouteSnapshotService(
            CourseRoutePointRepository courseRoutePointRepository,
            BikeMetricsRecorder bikeMetricsRecorder,
            Clock clock,
            @Value("${bike.course-route-cache.enabled:true}") boolean enabled,
            @Value("${bike.course-route-cache.ttl-sec:600}") long ttlSec
    ) {
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.bikeMetricsRecorder = bikeMetricsRecorder;
        this.clock = clock;
        this.enabled = enabled;
        this.ttl = Duration.ofSeconds(ttlSec);
    }

    public CourseRouteSnapshot get(Long courseId, String consumer) {
        if (!enabled) {
            bikeMetricsRecorder.recordCourseRouteCacheBypass(consumer);
            return loadSnapshot(courseId, consumer);
        }

        Instant now = clock.instant();
        CachedCourseRouteSnapshot cached = cache.get(courseId);
        if (cached != null && !cached.isExpired(now)) {
            bikeMetricsRecorder.recordCourseRouteCacheHit(consumer);
            return cached.snapshot();
        }

        CachedCourseRouteSnapshot loaded = cache.compute(courseId, (id, existing) -> {
            Instant computeNow = clock.instant();
            if (existing != null && !existing.isExpired(computeNow)) {
                bikeMetricsRecorder.recordCourseRouteCacheHit(consumer);
                return existing;
            }

            bikeMetricsRecorder.recordCourseRouteCacheMiss(consumer);
            CourseRouteSnapshot snapshot = loadSnapshot(id, consumer);
            return new CachedCourseRouteSnapshot(snapshot, computeNow.plus(ttl));
        });
        return loaded.snapshot();
    }

    public void evict(Long courseId, String reason) {
        cache.remove(courseId);
        bikeMetricsRecorder.recordCourseRouteCacheEviction(reason);
    }

    private CourseRouteSnapshot loadSnapshot(Long courseId, String consumer) {
        long startedAtNanos = System.nanoTime();
        bikeMetricsRecorder.recordCourseRouteSnapshotLoad(consumer);
        try {
            List<CourseRoutePointEntity> routePoints = List.copyOf(courseRoutePointRepository.findByCourseIdOrderByPointOrderAsc(courseId));
            List<CourseRoutePointResponse> responsePoints = routePoints.stream()
                    .map(routePoint -> new CourseRoutePointResponse(
                            routePoint.getPointOrder(),
                            routePoint.getLatitude(),
                            routePoint.getLongitude()
                    ))
                    .toList();
            return new CourseRouteSnapshot(
                    courseId,
                    routePoints,
                    responsePoints,
                    new RouteProjectionIndex(routePoints)
            );
        } finally {
            bikeMetricsRecorder.recordCourseRouteSnapshotLoadDuration(
                    consumer,
                    Duration.ofNanos(System.nanoTime() - startedAtNanos)
            );
        }
    }

    private record CachedCourseRouteSnapshot(CourseRouteSnapshot snapshot, Instant expiresAt) {

        private boolean isExpired(Instant now) {
            return now.isAfter(expiresAt);
        }
    }
}
