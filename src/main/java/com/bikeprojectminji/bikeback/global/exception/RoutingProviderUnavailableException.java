package com.bikeprojectminji.bikeback.global.exception;

public class RoutingProviderUnavailableException extends RetryableServiceUnavailableException {

    public static final String ERROR_CODE = "ROUTING_PROVIDER_UNAVAILABLE";

    public RoutingProviderUnavailableException(String message, int retryAfterSeconds) {
        super(message, ERROR_CODE, retryAfterSeconds);
    }
}
