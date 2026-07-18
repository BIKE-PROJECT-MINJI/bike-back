package com.bikeprojectminji.bikeback.global.exception;

public class RedisUnavailableException extends RetryableServiceUnavailableException {

    public static final String ERROR_CODE = "REDIS_UNAVAILABLE";
    public static final int RETRY_AFTER_SECONDS = 1;
    public static final String MESSAGE = "임시 저장소에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    public RedisUnavailableException() {
        super(MESSAGE, ERROR_CODE, RETRY_AFTER_SECONDS);
    }
}
