package com.bikeprojectminji.bikeback.address.infrastructure;

import com.bikeprojectminji.bikeback.address.service.AddressCandidate;
import com.bikeprojectminji.bikeback.address.service.AddressSearchClient;
import com.bikeprojectminji.bikeback.address.service.AddressSearchProviderResult;
import com.bikeprojectminji.bikeback.address.service.AddressSearchQuery;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "address.search", name = "provider", havingValue = "fake", matchIfMissing = true)
public class FakeAddressSearchClient implements AddressSearchClient {

    private static final String PROVIDER = "FAKE";

    @Override
    public AddressSearchProviderResult search(AddressSearchQuery query) {
        String normalized = query.rawQuery().toLowerCase();
        if (normalized.contains("rate")) {
            return AddressSearchProviderResult.rateLimited(PROVIDER);
        }
        if (normalized.contains("fail")) {
            return AddressSearchProviderResult.providerFailure(PROVIDER);
        }
        if (normalized.contains("empty") || normalized.contains("무결과")) {
            return AddressSearchProviderResult.empty(PROVIDER);
        }
        return AddressSearchProviderResult.success(PROVIDER, candidatesFor(query.rawQuery()));
    }

    private List<AddressCandidate> candidatesFor(String rawQuery) {
        return List.of(
                new AddressCandidate(
                        "fake-address-1",
                        rawQuery + " 자전거 여행 출발지",
                        "서울 종로구 북악산로 267",
                        BigDecimal.valueOf(37.6026),
                        BigDecimal.valueOf(126.9803),
                        PROVIDER,
                        "PLACE",
                        "HIGH"
                ),
                new AddressCandidate(
                        "fake-address-2",
                        rawQuery + " 근처 자전거길",
                        "서울 성북구 정릉동",
                        BigDecimal.valueOf(37.5921),
                        BigDecimal.valueOf(126.9811),
                        PROVIDER,
                        "ADDRESS",
                        "MEDIUM"
                )
        );
    }
}
