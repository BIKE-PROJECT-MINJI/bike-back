package com.bikeprojectminji.bikeback.weather.infrastructure;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class WeatherExecutionConfig {

    @Bean(destroyMethod = "shutdown")
    public ExecutorService weatherProviderExecutor(
            @Value("${weather.provider.executor.pool-size:4}") int poolSize,
            @Value("${weather.provider.executor.queue-capacity:32}") int queueCapacity
    ) {
        return new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queueCapacity), runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("weather-provider-executor");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
}
