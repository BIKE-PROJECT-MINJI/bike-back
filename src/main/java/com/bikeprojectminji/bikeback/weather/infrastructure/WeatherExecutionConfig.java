package com.bikeprojectminji.bikeback.weather.infrastructure;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeatherExecutionConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService weatherProviderExecutor() {
        return Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("weather-provider-executor");
            thread.setDaemon(true);
            return thread;
        });
    }
}
