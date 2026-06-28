package com.bikeprojectminji.bikeback.ride.service;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bike.ride.save.gate")
public class RideSaveConcurrencyGateProperties {

    private boolean enabled = true;
    private int maxConcurrency = 10;
    private int retryAfterSeconds = 3;
    private String message = "주행 기록 저장 요청이 많습니다. 로컬 기록을 유지하고 잠시 후 다시 저장해 주세요.";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxConcurrency() {
        return Math.max(1, maxConcurrency);
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getRetryAfterSeconds() {
        return Math.max(1, retryAfterSeconds);
    }

    public void setRetryAfterSeconds(int retryAfterSeconds) {
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        this.message = message;
    }
}
