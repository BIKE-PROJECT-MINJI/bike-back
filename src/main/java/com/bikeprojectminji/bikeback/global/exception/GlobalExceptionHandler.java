package com.bikeprojectminji.bikeback.global.exception;

import com.bikeprojectminji.bikeback.global.logging.RequestLogContext;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("unexpected_error request_id={} method={} path={}", RequestLogContext.currentRequestId(), request.getMethod(), request.getRequestURI(), exception);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiResponse<>(500, "서버 내부 오류가 발생했습니다.", null));
    }
}
