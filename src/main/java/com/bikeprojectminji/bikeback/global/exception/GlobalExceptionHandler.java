package com.bikeprojectminji.bikeback.global.exception;

import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadRequest(BadRequestException exception, HttpServletRequest request) {
        log.warn("bad_request request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(400, exception.getMessage(), null));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableMessage(HttpMessageNotReadableException exception, HttpServletRequest request) {
        log.warn("unreadable_message request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiResponse<>(400, "요청 본문이 필요합니다.", null));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(NotFoundException exception, HttpServletRequest request) {
        log.warn("not_found request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiResponse<>(404, exception.getMessage(), null));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException exception, HttpServletRequest request) {
        log.warn("unauthorized request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiResponse<>(401, exception.getMessage(), null));
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ApiResponse<Void>> handleForbidden(ForbiddenException exception, HttpServletRequest request) {
        log.warn("forbidden request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiResponse<>(403, exception.getMessage(), null));
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException exception, HttpServletRequest request) {
        log.warn("conflict request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(new ApiResponse<>(409, exception.getMessage(), null));
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiResponse<Void>> handleTooManyRequests(TooManyRequestsException exception, HttpServletRequest request) {
        log.warn("too_many_requests request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(new ApiResponse<>(429, exception.getMessage(), null));
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleServiceUnavailable(ServiceUnavailableException exception, HttpServletRequest request) {
        log.warn("service_unavailable request_id={} method={} path={} message={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, exception.getMessage(), null));
    }

    @ExceptionHandler(RetryableServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<RetryableErrorResponse>> handleRetryableServiceUnavailable(
            RetryableServiceUnavailableException exception,
            HttpServletRequest request
    ) {
        log.warn(
                "retryable_service_unavailable request_id={} method={} path={} error_code={} retry_after_seconds={} message={}",
                RequestLogContext.currentRequestId(),
                request.getMethod(),
                request.getRequestURI(),
                exception.getErrorCode(),
                exception.getRetryAfterSeconds(),
                exception.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(exception.getRetryAfterSeconds()))
                .body(new ApiResponse<>(
                        503,
                        exception.getMessage(),
                        new RetryableErrorResponse(exception.getErrorCode(), exception.getRetryAfterSeconds())
                ));
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ApiResponse<Void>> handleDatabaseUnavailable(RuntimeException exception, HttpServletRequest request) {
        Throwable rootCause = rootCause(exception);
        log.warn(
                "database_unavailable request_id={} method={} path={} exception={} root_cause={} message={}",
                RequestLogContext.currentRequestId(),
                request.getMethod(),
                request.getRequestURI(),
                exception.getClass().getSimpleName(),
                rootCause.getClass().getSimpleName(),
                rootCause.getMessage()
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ApiResponse<>(503, "데이터베이스 연결이 일시적으로 부족합니다. 잠시 후 다시 시도해 주세요.", null));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unexpected_error request_id={} method={} path={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "서버 내부 오류가 발생했습니다.", null));
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
