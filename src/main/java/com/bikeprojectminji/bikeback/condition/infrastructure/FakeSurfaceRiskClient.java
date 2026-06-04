package com.bikeprojectminji.bikeback.condition.infrastructure;

import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import com.bikeprojectminji.bikeback.condition.service.SurfaceRiskClient;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(50)
public class FakeSurfaceRiskClient implements SurfaceRiskClient {

    @Override
    public String source() {
        return "surface";
    }

    @Override
    public String label() {
        return "노면";
    }

    @Override
    public RouteConditionEvidence lookup(RouteConditionRequest request) {
        return RouteConditionEvidence.unknown(source(), label(), "OSM surface/smoothness 기반 노면 정보 미확인");
    }
}
