package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.validation.CoordinateValidator;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordRequest;
import com.bikeprojectminji.bikeback.ride.dto.CreateRideRecordSummaryRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.dto.RideRecordSummaryRequest;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class RideRecordRequestValidator {

    private static final BigDecimal MAX_BEARING_DEG = BigDecimal.valueOf(360);
    private static final BigDecimal MAX_PROGRESS_PCT = BigDecimal.valueOf(100);

    private RideRecordRequestValidator() {
    }

    static void validateCreateRequest(CreateRideRecordRequest request) {
        if (request == null) {
            throw new BadRequestException("자유 주행 기록 요청 본문이 필요합니다.");
        }
        validateSummaryFields(request.clientRideId(), request.startedAt(), request.endedAt(), request.summary());
        normalizeRoutePoints(request.routePoints());
    }

    static void validateSummaryRequest(CreateRideRecordSummaryRequest request) {
        if (request == null) {
            throw new BadRequestException("자유 주행 기록 요청 본문이 필요합니다.");
        }
        validateSummaryFields(request.clientRideId(), request.startedAt(), request.endedAt(), request.summary());
    }

    static List<RideRecordPointRequest> normalizeRoutePoints(List<RideRecordPointRequest> routePoints) {
        if (routePoints == null || routePoints.isEmpty()) {
            throw new BadRequestException("routePoints는 비어 있을 수 없습니다.");
        }
        Set<Integer> pointOrders = new HashSet<>();
        for (RideRecordPointRequest routePoint : routePoints) {
            validateRoutePoint(routePoint, pointOrders);
        }
        return routePoints.stream()
                .sorted(Comparator.comparing(RideRecordPointRequest::pointOrder))
                .toList();
    }

    private static void validateSummaryFields(
            String clientRideId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            RideRecordSummaryRequest summary
    ) {
        if (startedAt == null || endedAt == null) {
            throw new BadRequestException("startedAt과 endedAt은 비어 있을 수 없습니다.");
        }
        if (summary == null) {
            throw new BadRequestException("summary는 비어 있을 수 없습니다.");
        }
        if (clientRideId != null && clientRideId.length() > 80) {
            throw new BadRequestException("clientRideId는 80자 이하여야 합니다.");
        }
        if (summary.distanceM() == null || summary.distanceM() < 0) {
            throw new BadRequestException("distanceM은 0 이상이어야 합니다.");
        }
        if (summary.durationSec() == null || summary.durationSec() < 0) {
            throw new BadRequestException("durationSec은 0 이상이어야 합니다.");
        }
        if (summary.durationSec() < 10) {
            throw new BadRequestException("주행 시작 후 10초 미만 기록은 저장되지 않습니다.");
        }
    }

    private static void validateRoutePoint(RideRecordPointRequest routePoint, Set<Integer> pointOrders) {
        if (routePoint.pointOrder() == null || routePoint.pointOrder() < 1) {
            throw new BadRequestException("pointOrder는 1 이상이어야 합니다.");
        }
        if (routePoint.latitude() == null || routePoint.longitude() == null) {
            throw new BadRequestException("routePoints의 latitude와 longitude는 비어 있을 수 없습니다.");
        }
        CoordinateValidator.validateLatLon(
                "routePoints.latitude",
                routePoint.latitude(),
                "routePoints.longitude",
                routePoint.longitude()
        );
        validateNullableTelemetry(routePoint);
        if (!pointOrders.add(routePoint.pointOrder())) {
            throw new BadRequestException("routePoints의 pointOrder는 중복될 수 없습니다.");
        }
    }

    private static void validateNullableTelemetry(RideRecordPointRequest routePoint) {
        if (routePoint.accuracyM() != null && routePoint.accuracyM().signum() < 0) {
            throw new BadRequestException("accuracyM은 0 이상이어야 합니다.");
        }
        if (routePoint.speedMps() != null && routePoint.speedMps().signum() < 0) {
            throw new BadRequestException("speedMps는 0 이상이어야 합니다.");
        }
        if (routePoint.distanceToRouteM() != null && routePoint.distanceToRouteM().signum() < 0) {
            throw new BadRequestException("distanceToRouteM은 0 이상이어야 합니다.");
        }
        if (routePoint.bearingDeg() != null && (routePoint.bearingDeg().signum() < 0 || routePoint.bearingDeg().compareTo(MAX_BEARING_DEG) >= 0)) {
            throw new BadRequestException("bearingDeg는 0 이상 360 미만이어야 합니다.");
        }
        if (routePoint.routeProgressPct() != null && (routePoint.routeProgressPct().signum() < 0 || routePoint.routeProgressPct().compareTo(MAX_PROGRESS_PCT) > 0)) {
            throw new BadRequestException("routeProgressPct는 0 이상 100 이하여야 합니다.");
        }
    }
}
