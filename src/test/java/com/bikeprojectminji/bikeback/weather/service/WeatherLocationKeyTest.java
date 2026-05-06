package com.bikeprojectminji.bikeback.weather.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class WeatherLocationKeyTest {

    @Test
    @DisplayName("날씨 위치 키는 위경도를 소수 둘째 자리까지 반올림한다")
    void fromRoundsLatLonToTwoDecimals() {
        WeatherLocationKey key = WeatherLocationKey.from(
                new BigDecimal("37.56654"),
                new BigDecimal("126.97844")
        );

        assertThat(key.lat()).isEqualByComparingTo("37.57");
        assertThat(key.lon()).isEqualByComparingTo("126.98");
    }

    @Test
    @DisplayName("근접한 좌표는 같은 날씨 키로 합쳐진다")
    void fromCollapsesNearbyCoordinatesIntoSameKey() {
        WeatherLocationKey first = WeatherLocationKey.from(
                new BigDecimal("37.56644"),
                new BigDecimal("126.97841")
        );
        WeatherLocationKey second = WeatherLocationKey.from(
                new BigDecimal("37.56649"),
                new BigDecimal("126.97849")
        );

        assertThat(first).isEqualTo(second);
    }
}
