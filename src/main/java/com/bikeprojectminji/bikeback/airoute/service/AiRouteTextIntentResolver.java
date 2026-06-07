package com.bikeprojectminji.bikeback.airoute.service;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteTextPlanRequest;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

@Component
class AiRouteTextIntentResolver {

    AiRoutePlanRequest resolve(AiRouteTextPlanRequest request) {
        if (request == null) {
            throw new BadRequestException("텍스트 기반 AI 경로 요청이 필요합니다.");
        }
        String text = normalizeText(request.text());
        if (text.isBlank()) {
            throw new BadRequestException("코스 생성 텍스트는 비어 있을 수 없습니다.");
        }
        if (containsAny(text, "오르막", "업힐", "산", "남산")) {
            return new AiRoutePlanRequest(
                    request.lat(),
                    request.lon(),
                    BigDecimal.valueOf(37.5512),
                    BigDecimal.valueOf(126.9882),
                    "남산 N서울타워",
                    "SCENERY_FIRST",
                    "CLIMB_FIRST",
                    "CANONICAL_NAMSAN_NATIONAL_THEATER"
            );
        }
        if (containsAny(text, "평지", "완만", "편한")) {
            return new AiRoutePlanRequest(
                    request.lat(),
                    request.lon(),
                    BigDecimal.valueOf(37.5430),
                    BigDecimal.valueOf(126.9020),
                    "안양천합수부",
                    "BIKE_PATH_FIRST",
                    "FLAT_FIRST",
                    "TEXT_FLAT_RIVERSIDE"
            );
        }
        if (containsAny(text, "강", "한강", "시원", "풍경")) {
            return new AiRoutePlanRequest(
                    request.lat(),
                    request.lon(),
                    BigDecimal.valueOf(37.5126),
                    BigDecimal.valueOf(126.9965),
                    "반포한강공원",
                    "SCENERY_FIRST",
                    "BALANCED_ELEVATION",
                    "TEXT_RIVER_VIEW"
            );
        }
        return new AiRoutePlanRequest(
                request.lat(),
                request.lon(),
                null,
                null,
                "현재 위치 기반 추천 코스",
                "BALANCED",
                "BALANCED_ELEVATION",
                "TEXT_BALANCED"
        );
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeText(String text) {
        return text == null ? "" : text.trim();
    }
}
