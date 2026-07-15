package com.bikeprojectminji.bikeback.global.exception;

public class RetryableTooManyRequestsException extends TooManyRequestsException {

    private final String errorCode;
    private final int retryAfterSeconds;

    public RetryableTooManyRequestsException(String message, String errorCode, int retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = Math.max(1, retryAfterSeconds);
    }

    public String getErrorCode() {
        return errorCode;
    }

    public int getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
