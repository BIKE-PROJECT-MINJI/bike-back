package com.bikeprojectminji.bikeback.global.exception;

public record RetryableErrorResponse(
        String errorCode,
        int retryAfterSeconds
) {
}
