package com.bikeprojectminji.bikeback.global.logging;

import java.util.UUID;
import org.slf4j.MDC;

public final class RequestLogContext {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTRIBUTE = "requestId";
    public static final String REQUEST_ID_MDC_KEY = "requestId";

    private RequestLogContext() {
    }

    public static String resolveRequestId(String headerValue) {
        if (headerValue == null || headerValue.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return headerValue;
    }

    public static void bind(String requestId) {
        MDC.put(REQUEST_ID_MDC_KEY, requestId);
    }

    public static void clear() {
        MDC.remove(REQUEST_ID_MDC_KEY);
    }

    public static String currentRequestId() {
        String value = MDC.get(REQUEST_ID_MDC_KEY);
        return value == null ? "unknown" : value;
    }
}
