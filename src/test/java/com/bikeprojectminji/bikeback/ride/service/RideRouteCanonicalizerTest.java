package com.bikeprojectminji.bikeback.ride.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.ride.dto.RideRecordPointRequest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RideRouteCanonicalizerTest {

    private final RideRouteCanonicalizer canonicalizer = new RideRouteCanonicalizer();

    @Test
    @DisplayName("canonical path 생성은 직선 구간의 중간 포인트를 줄인다")
    void canonicalizeSimplifiesStraightLinePoints() {
        List<RideRecordPointRequest> result = canonicalizer.canonicalize(List.of(
                new RideRecordPointRequest(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                new RideRecordPointRequest(2, BigDecimal.valueOf(37.56655), BigDecimal.valueOf(126.9785)),
                new RideRecordPointRequest(3, BigDecimal.valueOf(37.56660), BigDecimal.valueOf(126.9790)),
                new RideRecordPointRequest(4, BigDecimal.valueOf(37.56665), BigDecimal.valueOf(126.9795))
        ));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).pointOrder()).isEqualTo(1);
        assertThat(result.get(1).pointOrder()).isEqualTo(2);
    }
}
