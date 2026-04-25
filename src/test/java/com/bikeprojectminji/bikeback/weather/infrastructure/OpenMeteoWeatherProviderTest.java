package com.bikeprojectminji.bikeback.weather.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.bikeprojectminji.bikeback.weather.service.WeatherLocationKey;
import com.bikeprojectminji.bikeback.weather.service.WeatherProviderResult;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OpenMeteoWeatherProviderTest {

    private RestClient.Builder restClientBuilder;
    private MockRestServiceServer mockServer;
    private OpenMeteoWeatherProvider openMeteoWeatherProvider;

    @BeforeEach
    void setUp() {
        restClientBuilder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(restClientBuilder).build();
        openMeteoWeatherProvider = new OpenMeteoWeatherProvider(
                restClientBuilder.baseUrl("https://api.open-meteo.com").build(),
                Clock.fixed(Instant.parse("2026-04-23T20:35:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    @DisplayName("current 데이터가 있으면 provider는 현재 시점 snapshot을 반환한다")
    void getCurrentReturnsCurrentSnapshot() {
        mockServer.expect(requestTo(weatherForecastRequestMatcher()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "current": {
                            "temperature_2m": 12.4,
                            "weather_code": 0,
                            "wind_speed_10m": 14.1,
                            "wind_direction_10m": 315
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WeatherProviderResult result = openMeteoWeatherProvider.getCurrent(locationKey());

        assertThat(result.success()).isTrue();
        assertThat(result.snapshot()).isNotNull();
        assertThat(result.snapshot().forecastFallbackUsed()).isFalse();
        assertThat(result.snapshot().weather().temperatureC()).isEqualTo(12);
        mockServer.verify();
    }

    @Test
    @DisplayName("current 데이터가 비어 있으면 hourly fallback snapshot을 반환한다")
    void getCurrentReturnsHourlyFallbackSnapshot() {
        mockServer.expect(requestTo(weatherForecastRequestMatcher()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {
                          "current": {},
                          "hourly": {
                            "time": ["2026-04-23T20:00"],
                            "temperature_2m": [18.1],
                            "weather_code": [61],
                            "wind_speed_10m": [19.2],
                            "wind_direction_10m": [90]
                          }
                        }
                        """, MediaType.APPLICATION_JSON));

        WeatherProviderResult result = openMeteoWeatherProvider.getCurrent(locationKey());

        assertThat(result.success()).isTrue();
        assertThat(result.snapshot()).isNotNull();
        assertThat(result.snapshot().forecastFallbackUsed()).isTrue();
        assertThat(result.snapshot().weather().precipType()).isEqualTo("rain");
        mockServer.verify();
    }

    @Test
    @DisplayName("provider 호출 예외가 발생하면 failure를 반환한다")
    void getCurrentReturnsFailureWhenProviderThrows() {
        mockServer.expect(requestTo(weatherForecastRequestMatcher()))
                .andExpect(method(HttpMethod.GET))
                .andRespond(request -> {
                    throw new RuntimeException("provider timeout");
                });

        WeatherProviderResult result = openMeteoWeatherProvider.getCurrent(locationKey());

        assertThat(result.success()).isFalse();
        assertThat(result.snapshot()).isNull();
        mockServer.verify();
    }

    private WeatherLocationKey locationKey() {
        return WeatherLocationKey.from(BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780));
    }

    private org.hamcrest.Matcher<String> weatherForecastRequestMatcher() {
        return allOf(
                containsString("https://api.open-meteo.com/v1/forecast"),
                containsString("latitude=37.5665"),
                containsString("longitude=126.9780"),
                containsString("current=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m"),
                containsString("hourly=temperature_2m,weather_code,wind_speed_10m,wind_direction_10m"),
                containsString("wind_speed_unit=kmh"),
                containsString("timezone=GMT")
        );
    }
}
