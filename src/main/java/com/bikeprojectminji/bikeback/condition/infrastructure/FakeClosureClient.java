package com.bikeprojectminji.bikeback.condition.infrastructure;

import com.bikeprojectminji.bikeback.condition.service.ClosureClient;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(40)
public class FakeClosureClient implements ClosureClient {

    @Override
    public String source() {
        return "closure";
    }

    @Override
    public String label() {
        return "통제";
    }

    @Override
    public RouteConditionEvidence lookup(RouteConditionRequest request) {
        return RouteConditionEvidence.unknown(source(), label(), "도로 통제 정보 미확인");
    }
}
