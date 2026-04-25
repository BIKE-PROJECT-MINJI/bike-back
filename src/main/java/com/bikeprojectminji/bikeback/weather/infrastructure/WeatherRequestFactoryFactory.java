package com.bikeprojectminji.bikeback.weather.infrastructure;

import org.springframework.http.client.SimpleClientHttpRequestFactory;

public final class WeatherRequestFactoryFactory {

    private WeatherRequestFactoryFactory() {
    }

    public static SimpleClientHttpRequestFactory create(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }
}
