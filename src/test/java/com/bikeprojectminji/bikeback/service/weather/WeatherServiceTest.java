package com.bikeprojectminji.bikeback.service.weather;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.dto.weather.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.dto.weather.WeatherData;
import com.bikeprojectminji.bikeback.dto.weather.WindData;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {

    @Mock
    private WeatherProviderPort weatherProviderPort;

    @Mock
    private LastSuccessWeatherStore lastSuccessWeatherStore;

    private WeatherService weatherService;

    @BeforeEach
    void setUp() {
        weatherService = new WeatherService(
                weatherProviderPort,
                lastSuccessWeatherStore,
                Clock.fixed(Instant.parse("2026-03-29T01:20:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("provider 성공이면 stale=false로 응답하고 마지막 성공값을 저장한다")
    void getCurrentReturnsFreshResponse() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        WeatherSnapshot snapshot = snapshot(false, "2026-03-29T10:19:00+09:00");
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.success(snapshot));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isFalse();
        assertThat(response.forecastFallbackUsed()).isFalse();
        verify(lastSuccessWeatherStore).save(key, snapshot);
    }

    @Test
    @DisplayName("provider 실패 시 60분 이내 마지막 성공값이 있으면 stale=true로 응답한다")
    void getCurrentReturnsStaleFallbackResponse() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.failure());
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(true, "2026-03-29T09:40:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response.stale()).isTrue();
        assertThat(response.forecastFallbackUsed()).isTrue();
    }

    @Test
    @DisplayName("provider 실패 시 마지막 성공값이 60분을 넘기면 정보 없음으로 처리한다")
    void getCurrentReturnsUnavailableWhenLastSuccessExpired() {
        WeatherLocationKey key = WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
        given(weatherProviderPort.getCurrent(key)).willReturn(WeatherProviderResult.failure());
        given(lastSuccessWeatherStore.find(key)).willReturn(Optional.of(snapshot(false, "2026-03-29T09:10:00+09:00")));

        CurrentWeatherResponse response = weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(response).isNull();
    }

    private WeatherSnapshot snapshot(boolean forecastFallbackUsed, String lastSucceededAt) {
        return new WeatherSnapshot(
                new WeatherData(12, "clear", "none"),
                new WindData(14, "북서", 315),
                forecastFallbackUsed,
                OffsetDateTime.parse(lastSucceededAt),
                OffsetDateTime.parse(lastSucceededAt)
        );
    }
}
