package com.bikeprojectminji.bikeback.global.database;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bike.database.backpressure")
public class DatabaseBackpressureProperties {

    private boolean enabled = true;
    private double activeThresholdRatio = 1.0;
    private int pendingThreshold = 1;
    private String message = "데이터베이스 처리량이 일시적으로 부족합니다. 잠시 후 다시 시도해 주세요.";

    public boolean shouldGuardPath(String path) {
        return enabled && path != null && (path.equals("/api") || path.startsWith("/api/"));
    }

    public boolean shouldReject(DatabasePoolSnapshot snapshot) {
        if (!enabled || snapshot == null || snapshot.maxConnections() <= 0) {
            return false;
        }
        if (snapshot.pendingThreads() >= Math.max(1, pendingThreshold)) {
            return true;
        }
        int activeThreshold = Math.max(1, (int) Math.ceil(snapshot.maxConnections() * normalizedActiveThresholdRatio()));
        return snapshot.idleConnections() == 0 && snapshot.activeConnections() >= activeThreshold;
    }

    public String rejectionReason(DatabasePoolSnapshot snapshot) {
        if (snapshot != null && snapshot.pendingThreads() >= Math.max(1, pendingThreshold)) {
            return "pending_threads";
        }
        return "pool_exhausted";
    }

    private double normalizedActiveThresholdRatio() {
        if (activeThresholdRatio <= 0.0) {
            return 1.0;
        }
        if (activeThresholdRatio > 1.0) {
            return 1.0;
        }
        return activeThresholdRatio;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public double getActiveThresholdRatio() {
        return activeThresholdRatio;
    }

    public void setActiveThresholdRatio(double activeThresholdRatio) {
        this.activeThresholdRatio = activeThresholdRatio;
    }

    public int getPendingThreshold() {
        return pendingThreshold;
    }

    public void setPendingThreshold(int pendingThreshold) {
        this.pendingThreshold = pendingThreshold;
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
