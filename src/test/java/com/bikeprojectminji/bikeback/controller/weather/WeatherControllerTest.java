package com.bikeprojectminji.bikeback.controller.weather;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.weather.CurrentWeatherResponse;
import com.bikeprojectminji.bikeback.dto.weather.WeatherData;
import com.bikeprojectminji.bikeback.dto.weather.WindData;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.service.weather.WeatherService;
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
        "auth.jwt.secret=01234567890123456789012345678901",
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
                false
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
                .andExpect(jsonPath("$.data.forecastFallbackUsed").value(false));
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
