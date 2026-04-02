package com.bikeprojectminji.bikeback.service.ride;

import com.bikeprojectminji.bikeback.dto.ride.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.dto.ride.RideRecordResponse;
import com.bikeprojectminji.bikeback.entity.ride.RideRecordEntity;
import com.bikeprojectminji.bikeback.entity.ride.RideRecordPointEntity;
import com.bikeprojectminji.bikeback.entity.user.UserEntity;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.repository.ride.RideRecordPointRepository;
import com.bikeprojectminji.bikeback.repository.ride.RideRecordRepository;
import com.bikeprojectminji.bikeback.service.auth.AuthService;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RideRecordService {

    private final AuthService authService;
    private final RideRecordRepository rideRecordRepository;
    private final RideRecordPointRepository rideRecordPointRepository;

    public RideRecordService(
            AuthService authService,
            RideRecordRepository rideRecordRepository,
            RideRecordPointRepository rideRecordPointRepository
    ) {
        this.authService = authService;
        this.rideRecordRepository = rideRecordRepository;
        this.rideRecordPointRepository = rideRecordPointRepository;
    }

    @Transactional
    public RideRecordResponse saveRideRecord(String subject, CreateRideRecordRequest request) {
        validateCreateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);

        RideRecordEntity rideRecord = rideRecordRepository.save(new RideRecordEntity(
                user.getId(),
                request.startedAt(),
                request.endedAt(),
                request.summary().distanceM(),
                request.summary().durationSec()
        ));

        List<RideRecordPointEntity> routePoints = normalizeRoutePoints(request.routePoints()).stream()
                .map(point -> new RideRecordPointEntity(rideRecord.getId(), point.pointOrder(), point.latitude(), point.longitude()))
                .toList();
        rideRecordPointRepository.saveAll(routePoints);

        return new RideRecordResponse(rideRecord.getId(), user.getId(), routePoints.size());
    }

    private void validateCreateRequest(CreateRideRecordRequest request) {
        if (request == null) {
            throw new BadRequestException("자유 주행 기록 요청 본문이 필요합니다.");
        }
        if (request.startedAt() == null || request.endedAt() == null) {
            throw new BadRequestException("startedAt과 endedAt은 비어 있을 수 없습니다.");
        }
        if (request.summary() == null) {
            throw new BadRequestException("summary는 비어 있을 수 없습니다.");
        }
        if (request.summary().distanceM() == null || request.summary().distanceM() < 0) {
            throw new BadRequestException("distanceM은 0 이상이어야 합니다.");
        }
        if (request.summary().durationSec() == null || request.summary().durationSec() < 0) {
            throw new BadRequestException("durationSec은 0 이상이어야 합니다.");
        }
        normalizeRoutePoints(request.routePoints());
    }

    private List<RideRecordPointRequest> normalizeRoutePoints(List<RideRecordPointRequest> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            throw new BadRequestException("routePoints는 비어 있을 수 없습니다.");
        }
        Set<Integer> pointOrders = new HashSet<>();
        for (RideRecordPointRequest routePoint : routePoints) {
            if (routePoint.pointOrder() == null || routePoint.pointOrder() < 1) {
                throw new BadRequestException("pointOrder는 1 이상이어야 합니다.");
            }
            if (routePoint.latitude() == null || routePoint.longitude() == null) {
                throw new BadRequestException("routePoints의 latitude와 longitude는 비어 있을 수 없습니다.");
            }
            if (!pointOrders.add(routePoint.pointOrder())) {
                throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
            }
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(RideRecordPointRequest::pointOrder))
                .toList();
    }
}
