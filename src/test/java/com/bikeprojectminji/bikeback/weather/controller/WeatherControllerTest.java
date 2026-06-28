package com.bikeprojectminji.bikeback.weather.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.weather.dto.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.weather.dto.WeatherData;
import com.bikeprojectminji.bikeback.weather.dto.WindData;
import com.bikeprojectminji.bikeback.weather.service.WeatherService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(WeatherController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class WeatherControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherService weatherService;

    @Test
    @DisplayName("날씨 API는 최소 계약 필드를 success 래퍼로 응답한다")
    void getCurrentReturnsWrappedResponse() throws Exception {
        CurrentWeatherResponse response = new CurrentWeatherResponse(
                new WeatherData(12, "clear", "none"),
                new WindData(14, "북서", 315),
                false,
                false,
                "FRESH_PROVIDER",
                null,
                OffsetDateTime.parse("2026-05-26T10:00:00+09:00"),
                0
        );
        given(weatherService.getCurrent(any(), any())).willReturn(response);

        mockMvc.perform(get("/api/v1/weather/current")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.weather.temperatureC").value(12))
                .andExpect(jsonPath("$.data.wind.speedKmh").value(14))
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.forecastFallbackUsed").value(false))
                .andExpect(jsonPath("$.data.freshnessStatus").value("FRESH_PROVIDER"))
                .andExpect(jsonPath("$.data.observedAt").value("2026-05-26T10:00:00+09:00"))
                .andExpect(jsonPath("$.data.cacheAgeSec").value(0));
    }

    @Test
    @DisplayName("현재 날씨를 사용할 수 없으면 unavailable metadata를 success wrapper로 반환한다")
    void getCurrentReturnsUnavailableMetadataWhenWeatherUnavailable() throws Exception {
        CurrentWeatherResponse response = new CurrentWeatherResponse(
                null,
                null,
                false,
                false,
                "UNAVAILABLE",
                "PROVIDER_FAILURE",
                null,
                0
        );
        given(weatherService.getCurrent(any(), any())).willReturn(response);

        mockMvc.perform(get("/api/v1/weather/current")
                        .param("lat", "37.5665")
                        .param("lon", "126.9780"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.weather").doesNotExist())
                .andExpect(jsonPath("$.data.wind").doesNotExist())
                .andExpect(jsonPath("$.data.stale").value(false))
                .andExpect(jsonPath("$.data.freshnessStatus").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.data.staleReason").value("PROVIDER_FAILURE"));
    }

    @Test
    @DisplayName("lat 범위를 벗어나면 400을 응답한다")
    void getCurrentReturnsBadRequestWhenLatOutOfRange() throws Exception {
        mockMvc.perform(get("/api/v1/weather/current")
                        .param("lat", "91")
                        .param("lon", "126.9780"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
