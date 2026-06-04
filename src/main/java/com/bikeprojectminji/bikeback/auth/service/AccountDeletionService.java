package com.bikeprojectminji.bikeback.auth.service;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.event.repository.ClientEventRepository;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordEntity;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordProcessedPointRepository;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountDeletionService {

    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;
    private final RideRecordProcessedPointRepository rideRecordProcessedPointRepository;
    private final CourseRepository courseRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final ClientEventRepository clientEventRepository;

    public AccountDeletionService(
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository,
            RideRecordProcessedPointRepository rideRecordProcessedPointRepository,
            CourseRepository courseRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            ClientEventRepository clientEventRepository
    ) {
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
        this.rideRecordProcessedPointRepository = rideRecordProcessedPointRepository;
        this.courseRepository = courseRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.clientEventRepository = clientEventRepository;
    }

    @Transactional
    public void deleteOwnedData(Long userId) {
        List<RideRecordEntity> rideRecords = rideRecordRepository.findByOwnerUserId(userId);
        List<Long> rideRecordIds = rideRecords.stream()
                .map(RideRecordEntity::getId)
                .toList();

        List<CourseEntity> ownedCourses = courseRepository.findByOwnerUserId(userId);
        List<Long> removableCourseIds = ownedCourses.stream()
                .filter(course -> course.getVisibility() != CourseVisibility.PUBLIC)
                .map(CourseEntity::getId)
                .toList();
        List<CourseEntity> removableCourses = ownedCourses.stream()
                .filter(course -> course.getVisibility() != CourseVisibility.PUBLIC)
                .toList();

        if (!removableCourseIds.isEmpty()) {
            courseRoutePointRepository.deleteByCourseIdIn(removableCourseIds);
            courseRepository.deleteAll(removableCourses);
        }

        ownedCourses.stream()
                .filter(course -> course.getVisibility() == CourseVisibility.PUBLIC)
                .forEach(CourseEntity::anonymizeOwner);

        if (!rideRecordIds.isEmpty()) {
            rideRecordPointRepository.deleteByRideRecordIdIn(rideRecordIds);
            rideRecordProcessedPointRepository.deleteByRideRecordIdIn(rideRecordIds);
            rideRecordRepository.deleteAll(rideRecords);
        }
        clientEventRepository.deleteByUserId(userId);
    }
}
