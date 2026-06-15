package com.bikeprojectminji.bikeback.weather.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.weather.service.WeatherLocationKey;
import com.bikeprojectminji.bikeback.weather.service.WeatherProviderResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FakeWeatherProviderTest {

    private final FakeWeatherProvider fakeWeatherProvider = new FakeWeatherProvider(
            Clock.fixed(Instant.parse("2026-06-15T00:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    @DisplayName("fake weather provider는 외부 네트워크 없이 현재 날씨 snapshot을 반환한다")
    void getCurrentReturnsDeterministicSnapshotWithoutNetwork() {
        WeatherProviderResult result = fakeWeatherProvider.getCurrent(
                WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780))
        );

        assertThat(result.success()).isTrue();
        assertThat(result.snapshot().weather().sky()).isEqualTo("clear");
        assertThat(result.snapshot().wind().directionText()).isEqualTo("북동");
        assertThat(result.snapshot().forecastFallbackUsed()).isFalse();
        assertThat(result.snapshot().observedAt()).isEqualTo("2026-06-15T00:00Z");
    }
}
