package com.bikeprojectminji.bikeback.global.logging;

import java.util.concurrent.ThreadLocalRandom;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "bike.observability.logging")
public class ObservabilityLoggingProperties {

    private LogPolicy http = LogPolicy.httpDefault();
    private LogPolicy operation = LogPolicy.operationDefault();

    public LogPolicy getHttp() {
        return http;
    }

    public void setHttp(LogPolicy http) {
        this.http = http == null ? LogPolicy.httpDefault() : http;
    }

    public LogPolicy getOperation() {
        return operation;
    }

    public void setOperation(LogPolicy operation) {
        this.operation = operation == null ? LogPolicy.operationDefault() : operation;
    }

    public boolean shouldLogHttpRequest(int status, long durationMs) {
        return http.shouldLog(status >= 500, status >= 400, durationMs);
    }

    public boolean shouldLogOperation(String outcome, long durationMs) {
        return operation.shouldLog("failure".equals(outcome), false, durationMs);
    }

    public enum LogMode {
        ALL,
        SLOW_OR_ERROR,
        ERRORS_ONLY,
        OFF;

    }

    public static class LogPolicy {

        private LogMode mode = LogMode.SLOW_OR_ERROR;
        private long slowThresholdMs = 500;
        private double sampleRate = 0.0d;

        static LogPolicy httpDefault() {
            LogPolicy policy = new LogPolicy();
            policy.slowThresholdMs = 500;
            return policy;
        }

        static LogPolicy operationDefault() {
            LogPolicy policy = new LogPolicy();
            policy.slowThresholdMs = 200;
            return policy;
        }

        public LogMode getMode() {
            return mode;
        }

        public void setMode(LogMode mode) {
            this.mode = mode == null ? LogMode.SLOW_OR_ERROR : mode;
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = Math.max(0, slowThresholdMs);
        }

        public double getSampleRate() {
            return sampleRate;
        }

        public void setSampleRate(double sampleRate) {
            this.sampleRate = Math.max(0.0d, Math.min(1.0d, sampleRate));
        }

        boolean shouldLog(boolean serverError, boolean clientError, long durationMs) {
            if (mode == LogMode.OFF) {
                return false;
            }
            if (mode == LogMode.ALL) {
                return true;
            }
            if (serverError || clientError) {
                return true;
            }
            if (mode == LogMode.SLOW_OR_ERROR && durationMs >= slowThresholdMs) {
                return true;
            }
            return sampleRate >= 1.0d || (sampleRate > 0.0d && ThreadLocalRandom.current().nextDouble() < sampleRate);
        }
    }
}
