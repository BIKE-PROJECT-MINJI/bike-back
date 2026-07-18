package com.bikeprojectminji.bikeback.global.exception;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bikeprojectminji.bikeback.global.response.ApiResponse;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.CannotCreateTransactionException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

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
    @DisplayName("예외 log는 영수증 조회 clientRideId 원문을 남기지 않는다")
    void exceptionLogMasksClientRideId() {
        String rawClientRideId = "android-ride-private-001";
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET",
                "/api/v1/ride-records/by-client-ride-id/" + rawClientRideId
        );

        handler.handleNotFound(new NotFoundException("자유 주행 기록을 찾을 수 없습니다."), request);

        assertThat(appender.list).hasSize(1);
        assertThat(appender.list.get(0).getFormattedMessage())
                .contains("path=/api/v1/ride-records/by-client-ride-id/{clientRideId}")
                .doesNotContain(rawClientRideId);
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

    @Test
    @DisplayName("라우팅 입력 오류는 400과 stable errorCode를 반환하고 재시도 헤더는 없다")
    void invalidRouteRequestReturnsStableErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-routes/plan");

        ResponseEntity<ApiResponse<ErrorCodeResponse>> response = handler.handleInvalidRouteRequest(
                new InvalidRouteRequestException("출발 좌표가 필요합니다."), request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(response.getBody().data()).isEqualTo(new ErrorCodeResponse("INVALID_ROUTE_REQUEST"));
    }

    @Test
    @DisplayName("경로 없음은 422 ROUTE_NOT_FOUND로 응답하고 자동 재시도시키지 않는다")
    void routeNotFoundReturnsUnprocessableEntity() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-routes/plan");

        ResponseEntity<ApiResponse<ErrorCodeResponse>> response = handler.handleRouteNotFound(
                new RouteNotFoundException("조건을 충족하는 경로가 없습니다."), request);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().containsKey("Retry-After")).isFalse();
        assertThat(response.getBody().data()).isEqualTo(new ErrorCodeResponse("ROUTE_NOT_FOUND"));
    }

    @Test
    @DisplayName("routing quota는 429와 Retry-After 및 stable errorCode를 반환한다")
    void routingQuotaReturnsRetryableTooManyRequests() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-routes/plan");

        ResponseEntity<ApiResponse<RetryableErrorResponse>> response = handler.handleRetryableTooManyRequests(
                new RetryableTooManyRequestsException("라우팅 요청이 제한되었습니다.", "ROUTING_QUOTA_EXCEEDED", 12), request);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("12");
        assertThat(response.getBody().data()).isEqualTo(new RetryableErrorResponse("ROUTING_QUOTA_EXCEEDED", 12));
    }

    @Test
    @DisplayName("모든 routing provider 장애는 retryable 503 stable errorCode로 응답한다")
    void routingProviderUnavailableReturnsStableErrorCode() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/ai-routes/plan");

        ResponseEntity<ApiResponse<RetryableErrorResponse>> response = handler.handleRetryableServiceUnavailable(
                new RoutingProviderUnavailableException("라우팅 provider가 일시적으로 불안정합니다.", 5), request);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getHeaders().getFirst("Retry-After")).isEqualTo("5");
        assertThat(response.getBody().data()).isEqualTo(new RetryableErrorResponse("ROUTING_PROVIDER_UNAVAILABLE", 5));
    }
}
