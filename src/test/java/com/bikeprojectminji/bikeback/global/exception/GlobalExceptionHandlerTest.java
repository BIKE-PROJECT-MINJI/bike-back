package com.bikeprojectminji.bikeback.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("DB connection 획득 실패는 운영자가 구분할 수 있게 503으로 응답한다")
    void databaseConnectionFailureReturnsServiceUnavailable() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses/1/route-points");
        RuntimeException exception = new CannotCreateTransactionException(
                "Could not open JPA EntityManager for transaction",
                new SQLTransientConnectionException("HikariPool-1 - Connection is not available")
        );

        ResponseEntity<ApiResponse<Void>> response = handler.handleDatabaseUnavailable(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(503);
        assertThat(response.getBody().message()).contains("데이터베이스 연결이 일시적으로 부족합니다");
    }

    @Test
    @DisplayName("재시도 가능한 503은 Retry-After 헤더와 errorCode metadata를 반환한다")
    void retryableServiceUnavailableReturnsRetryAfterAndErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ride-records");
        RetryableServiceUnavailableException exception = new RetryableServiceUnavailableException(
                "주행 기록 저장 요청이 많습니다.",
                "RIDE_SAVE_BUSY",
                3
        );

        ResponseEntity<ApiResponse<RetryableErrorResponse>> response = handler.handleRetryableServiceUnavailable(exception, request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("3");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(503);
        assertThat(response.getBody().data()).isEqualTo(new RetryableErrorResponse("RIDE_SAVE_BUSY", 3));
    }
}
