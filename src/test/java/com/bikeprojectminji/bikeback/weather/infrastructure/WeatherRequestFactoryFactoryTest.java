package com.bikeprojectminji.bikeback.weather.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

class WeatherRequestFactoryFactoryTest {

    @Test
    @DisplayName("weather request factory는 connect/read timeout을 명시값으로 설정한다")
    void createSetsConfiguredTimeouts() throws Exception {
        SimpleClientHttpRequestFactory factory = WeatherRequestFactoryFactory.create(300, 1000);

        assertThat(readField(factory, "connectTimeout")).isEqualTo(300);
        assertThat(readField(factory, "readTimeout")).isEqualTo(1000);
    }

    private int readField(SimpleClientHttpRequestFactory factory, String fieldName) throws Exception {
        Field field = SimpleClientHttpRequestFactory.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (int) field.get(factory);
    }
}
