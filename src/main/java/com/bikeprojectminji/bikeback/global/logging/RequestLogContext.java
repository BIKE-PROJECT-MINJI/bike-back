package com.bikeprojectminji.bikeback.global.logging;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestLogContext {

    private static final int MAX_CORRELATION_ID_LENGTH = 80;
    private static final String CORRELATION_ID_PATTERN = "^[A-Za-z0-9._:-]{1,80}$";

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";
    public static final String TRACE_ID_ATTRIBUTE = "traceId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";
    public static final String TRACE_ID_MDC_KEY = "traceId";

    private RequestLogContext() {
    }

    public static String resolveRequestId(String headerValue) {
        return resolveCorrelationId(headerValue);
    }

    public static String resolveTraceId(String headerValue) {
        return resolveCorrelationId(headerValue);
    }

    private static String resolveCorrelationId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }
        String trimmed = headerValue.trim();
        if (trimmed.length() > MAX_CORRELATION_ID_LENGTH || !trimmed.matches(CORRELATION_ID_PATTERN)) {
            return UUID.randomUUID().toString();
        }
        return trimmed;
    }

    public static void bind(String requestId, String traceId) {
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
        MDC.put(TRACE_ID_MDC_KEY, traceId);
    }

    public static void clear() {
        MDC.remove(REQUEST_ID_MDC_KEY);
        MDC.remove(TRACE_ID_MDC_KEY);
    }

    public static String currentRequestId() {
        String value = MDC.get(REQUEST_ID_MDC_KEY);
        return value == null ? "unknown" : value;
    }

    public static String currentTraceId() {
        String value = MDC.get(TRACE_ID_MDC_KEY);
        return value == null ? "unknown" : value;
    }
}
