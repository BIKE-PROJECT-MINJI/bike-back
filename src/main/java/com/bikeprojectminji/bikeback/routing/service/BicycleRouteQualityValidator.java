package com.bikeprojectminji.bikeback.routing.service;

import org.springframework.stereotype.Component;

@Component
public class BicycleRouteQualityValidator {

    public BicycleRouteQuality validate(BicycleRouteCandidate candidate) {
        if (candidate == null) {
            return new BicycleRouteQuality("INVALID", "경로 후보가 없습니다.");
        }
        if (candidate.polyline().size() < 2) {
            return new BicycleRouteQuality("INVALID", "경로 좌표가 2개 미만입니다.");
        }
        if (candidate.distanceMeters() <= 0) {
            return new BicycleRouteQuality("INVALID", "경로 거리가 0 이하입니다.");
        }
        if (candidate.durationSeconds() <= 0) {
            return new BicycleRouteQuality("INVALID", "예상 시간이 0 이하입니다.");
        }
        if (!candidate.elevationSummary().hasElevation()) {
            return new BicycleRouteQuality("VALID_WITH_WARNINGS", "고도 정보가 없어 평지/오르막 선호 검증 정확도가 낮습니다.");
        }
        return new BicycleRouteQuality("VALID", "경로 좌표, 거리, 시간, 고도 정보가 확인되었습니다.");
    }
}
