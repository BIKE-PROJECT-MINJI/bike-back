package com.bikeprojectminji.bikeback.airoute.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import com.bikeprojectminji.bikeback.weather.service.WeatherService;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WeatherCurrentWeatherLookupTest {

    @Mock
    private WeatherService weatherService;

    @Test
    @DisplayName("UNAVAILABLE 날씨 응답은 AI route에서 날씨 없음으로 취급한다")
    void findReturnsEmptyWhenWeatherUnavailable() {
        WeatherCurrentWeatherLookup lookup = new WeatherCurrentWeatherLookup(weatherService);
        given(weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
                .willReturn(new CurrentWeatherResponse(
                        null,
                        null,
                        false,
                        false,
                        "UNAVAILABLE",
                        "PROVIDER_FAILURE",
                        null,
                        0
                ));

        Optional<CurrentWeatherResponse> result = lookup.find(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("사용 가능한 날씨 응답은 그대로 전달한다")
    void findReturnsCurrentWeatherWhenUsable() {
        WeatherCurrentWeatherLookup lookup = new WeatherCurrentWeatherLookup(weatherService);
        CurrentWeatherResponse weather = new CurrentWeatherResponse(
                new WeatherData(21, "clear", "none"),
                new WindData(10, "북동", 45),
                false,
                false
        );
        given(weatherService.getCurrent(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
                .willReturn(weather);

        Optional<CurrentWeatherResponse> result = lookup.find(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));

        assertThat(result).contains(weather);
    }
}
