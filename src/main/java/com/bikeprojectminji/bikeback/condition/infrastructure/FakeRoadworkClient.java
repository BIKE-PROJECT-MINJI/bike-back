package com.bikeprojectminji.bikeback.condition.infrastructure;

import com.bikeprojectminji.bikeback.condition.service.RoadworkClient;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(30)
public class FakeRoadworkClient implements RoadworkClient {

    @Override
    public String source() {
        return "roadwork";
    }

    @Override
    public String label() {
        return "공사";
    }

    @Override
    public RouteConditionEvidence lookup(RouteConditionRequest request) {
        return RouteConditionEvidence.unknown(source(), label(), "공사/도로 작업 정보 미확인");
    }
}
