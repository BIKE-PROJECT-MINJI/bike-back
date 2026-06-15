package com.bikeprojectminji.bikeback.ride.service;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import com.bikeprojectminji.bikeback.ride.entity.RideRecordPointEntity;
import java.util.List;

final class RideRecordPointFactory {

    private RideRecordPointFactory() {
    }

    static List<RideRecordPointEntity> createRoutePointEntities(
            Long rideRecordId,
            List<RideRecordPointRequest> routePoints
    ) {
        return routePoints.stream()
                .map(point -> new RideRecordPointEntity(
                        rideRecordId,
                        point.pointOrder(),
                        point.latitude(),
                        point.longitude(),
                        point.capturedAt(),
                        point.accuracyM(),
                        point.speedMps(),
                        point.bearingDeg(),
                        point.altitudeM(),
                        point.distanceToRouteM(),
                        point.routeProgressPct()
                ))
                .toList();
    }
}
