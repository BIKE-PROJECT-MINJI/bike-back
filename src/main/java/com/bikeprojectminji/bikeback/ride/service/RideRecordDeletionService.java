package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class RideRecordDeletionService {

    private static final long RETENTION_DAYS = 30;

    private final AuthService authService;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final CourseRepository courseRepository;
    private final Clock clock;

    public RideRecordDeletionService(
            AuthService authService,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            CourseRepository courseRepository,
            Clock clock
    ) {
        this.authService = authService;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.courseRepository = courseRepository;
        this.clock = clock;
    }

    public void deleteRideRecord(String subject, Long rideRecordId) {
        UserEntity user = authService.findUserBySubject(subject);
        RideRecordEntity rideRecord = rideRecordRepository.findById(rideRecordId)
                .orElseThrow(() -> new NotFoundException("자유 주행 기록을 찾을 수 없습니다."));
        if (!rideRecord.getOwnerUserId().equals(user.getId())) {
            throw new ForbiddenException("자유 주행 기록에 접근할 수 없습니다.");
        }

        deleteRideRecords(List.of(rideRecord));
    }

    public int deleteExpiredRideRecords() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(RETENTION_DAYS);
        List<RideRecordEntity> expiredRideRecords = rideRecordRepository.findByEndedAtLessThanEqual(cutoff);
        deleteRideRecords(expiredRideRecords);
        return expiredRideRecords.size();
    }

    private void deleteRideRecords(List<RideRecordEntity> rideRecords) {
        if (rideRecords.isEmpty()) {
            return;
        }
        List<Long> rideRecordIds = rideRecords.stream()
                .map(RideRecordEntity::getId)
                .toList();
        detachLinkedCourses(rideRecordIds);
        rideRecordPointRepository.deleteByRideRecordIdIn(rideRecordIds);
        rideRecordProcessedPointRepository.deleteByRideRecordIdIn(rideRecordIds);
        rideRecordRepository.deleteAllByIdInBatch(rideRecordIds);
    }

    private void detachLinkedCourses(List<Long> rideRecordIds) {
        // 사용자가 저장한 코스는 유지하되, 삭제된 자유 주행 원본 ID만 끊어 죽은 참조를 남기지 않는다.
        List<CourseEntity> linkedCourses = courseRepository.findBySourceRideRecordIdIn(rideRecordIds);
        linkedCourses.forEach(CourseEntity::detachRideRecordSource);
    }
}
