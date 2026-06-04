package com.bikeprojectminji.bikeback.condition.infrastructure;

import com.bikeprojectminji.bikeback.condition.service.DustClient;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionEvidence;
import com.bikeprojectminji.bikeback.condition.service.RouteConditionRequest;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(20)
public class FakeDustClient implements DustClient {

    @Override
    public String source() {
        return "dust";
    }

    @Override
    public String label() {
        return "미세먼지";
    }

    @Override
    public RouteConditionEvidence lookup(RouteConditionRequest request) {
        return RouteConditionEvidence.unknown(source(), label(), "AirKorea provider 연동 전까지 미세먼지 정보 미확인");
    }
}
