package com.bikeprojectminji.bikeback.condition.service;

import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class RouteConditionEvidenceService {

    private static final String LLM_EVIDENCE_RULE = "LLM은 provider evidence에 없는 공사/통제/노면 사실을 생성하지 않고 UNKNOWN/FAILED를 그대로 설명한다.";

    private final List<RouteConditionClient> conditionClients;

    public RouteConditionEvidenceService(List<RouteConditionClient> conditionClients) {
        this.conditionClients = conditionClients;
    }

    public RouteConditionReport collect(RouteConditionRequest request) {
        validate(request);
        List<RouteConditionEvidence> evidence = conditionClients.stream()
                .map(client -> safeLookup(client, request))
                .toList();
        int unknownCount = countByStatus(evidence, "UNKNOWN");
        int failureCount = countByStatus(evidence, "FAILED");
        return new RouteConditionReport(
                unknownCount == 0 && failureCount == 0 ? "READY" : "PARTIAL",
                true,
                unknownCount,
                failureCount,
                LLM_EVIDENCE_RULE,
                evidence
        );
    }

    private void validate(RouteConditionRequest request) {
        if (request == null || request.lat() == null || request.lon() == null) {
            throw new BadRequestException("조건 조회 좌표가 필요합니다.");
        }
        validateCoordinate(request.lat(), request.lon());
    }

    private void validateCoordinate(BigDecimal lat, BigDecimal lon) {
        if (lat.compareTo(BigDecimal.valueOf(-90)) < 0 || lat.compareTo(BigDecimal.valueOf(90)) > 0) {
            throw new BadRequestException("조건 조회 좌표 lat는 -90 이상 90 이하여야 합니다.");
        }
        if (lon.compareTo(BigDecimal.valueOf(-180)) < 0 || lon.compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new BadRequestException("조건 조회 좌표 lon은 -180 이상 180 이하여야 합니다.");
        }
    }

    private RouteConditionEvidence safeLookup(RouteConditionClient client, RouteConditionRequest request) {
        try {
            RouteConditionEvidence evidence = client.lookup(request);
            if (evidence == null) {
                return RouteConditionEvidence.unknown(client.source(), client.label(), client.label() + " 정보 미확인");
            }
            return evidence;
        } catch (RuntimeException exception) {
            return RouteConditionEvidence.failed(client.source(), client.label(), client.label() + " provider 확인 실패");
        }
    }

    private int countByStatus(List<RouteConditionEvidence> evidence, String status) {
        return (int) evidence.stream()
                .filter(item -> status.equals(item.status()))
                .count();
    }
}
