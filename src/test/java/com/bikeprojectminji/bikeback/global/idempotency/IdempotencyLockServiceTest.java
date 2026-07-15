package com.bikeprojectminji.bikeback.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.bikeprojectminji.bikeback.global.exception.ServiceUnavailableException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.slf4j.LoggerFactory;

@SuppressWarnings({"rawtypes", "unchecked"})
class IdempotencyLockServiceTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachLogAppender() {
        appender.start();
        ((Logger) LoggerFactory.getLogger(IdempotencyLockService.class)).addAppender(appender);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(IdempotencyLockService.class)).detachAppender(appender);
        appender.stop();
        appender.list.clear();
    }

    @Test
    @DisplayName("idempotency lock은 기존 리소스가 있으면 Redis를 호출하지 않고 반환한다")
    void returnsExistingWithoutRedisWhenResourceAlreadyExists() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        IdempotencyLockService service = service(redisTemplate);

        String result = service.executeOrWait(
                "course_from_ride",
                "course-from-ride:1:1001",
                () -> Optional.of("existing-course"),
                () -> "created-course"
        );

        assertThat(result).isEqualTo("existing-course");
        verify(redisTemplate, never()).opsForValue();
    }

    @Test
    @DisplayName("idempotency lock은 Redis lock을 얻은 요청만 creator를 실행하고 lock을 해제한다")
    void executesCreatorWhenRedisLockIsAcquired() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(
                eq("bike:idempotency-lock:course-from-ride:1:1001"),
                eq("token-1"),
                eq(Duration.ofSeconds(10))
        )).willReturn(true);
        IdempotencyLockService service = service(redisTemplate);

        String result = service.executeOrWait(
                "course_from_ride",
                "course-from-ride:1:1001",
                Optional::<String>empty,
                () -> "created-course"
        );

        assertThat(result).isEqualTo("created-course");
        verify(redisTemplate).execute(any(RedisScript.class), eq(List.of("bike:idempotency-lock:course-from-ride:1:1001")), eq("token-1"));
    }

    @Test
    @DisplayName("idempotency lock은 lock 경쟁 중 기존 리소스가 보이면 200 응답 경로로 반환한다")
    void waitsUntilExistingResourceAppearsWhenRedisLockIsContended() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(10)))).willReturn(false);
        IdempotencyLockService service = service(redisTemplate);
        AtomicInteger lookups = new AtomicInteger();

        String result = service.executeOrWait(
                "course_from_ride",
                "course-from-ride:1:1001",
                () -> lookups.incrementAndGet() < 3 ? Optional.empty() : Optional.of("existing-course"),
                () -> "created-course"
        );

        assertThat(result).isEqualTo("existing-course");
        assertThat(lookups.get()).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("idempotency lock은 작업별 대기 시간이 주어지면 기본값 대신 해당 시간을 사용한다")
    void usesOperationSpecificWaitTimeoutWhenProvided() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(10)))).willReturn(false);
        IdempotencyLockService service = service(redisTemplate);
        AtomicInteger lookups = new AtomicInteger();

        String result = service.executeOrWait(
                "course_from_ride",
                "course-from-ride:1:1001",
                Duration.ofMillis(30),
                () -> lookups.incrementAndGet() < 8 ? Optional.empty() : Optional.of("existing-course"),
                () -> "created-course"
        );

        assertThat(result).isEqualTo("existing-course");
        assertThat(lookups.get()).isGreaterThanOrEqualTo(8);
    }

    @Test
    @DisplayName("idempotency lock은 Redis 장애 시 DB unique fallback 경로로 creator를 실행한다")
    void fallsBackToCreatorWhenRedisIsUnavailable() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        given(redisTemplate.opsForValue()).willThrow(new IllegalStateException("redis down"));
        IdempotencyLockService service = service(redisTemplate);

        String result = service.executeOrWait(
                "course_from_ride",
                "ride-record:1:private-client-id\\nforged",
                Optional::<String>empty,
                () -> "created-course"
        );

        assertThat(result).isEqualTo("created-course");
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> !message.contains("private-client-id") && !message.contains("\\nforged"));
    }

    @Test
    @DisplayName("idempotency lock 해제 실패 로그는 lock key 원문을 남기지 않는다")
    void releaseFailureLogDoesNotExposeRawKey() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(10)))).willReturn(true);
        given(redisTemplate.execute(any(RedisScript.class), any(List.class), anyString()))
                .willThrow(new IllegalStateException("redis release down"));
        IdempotencyLockService service = service(redisTemplate);

        service.executeOrWait(
                "ride_record_save_full",
                "ride-record:1:private-client-id\\nforged",
                Optional::<String>empty,
                () -> "created"
        );

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .allMatch(message -> !message.contains("private-client-id") && !message.contains("forged"));
    }

    @Test
    @DisplayName("idempotency lock은 대기 시간 안에 기존 리소스를 찾지 못하면 503으로 빠진다")
    void throwsServiceUnavailableWhenExistingResourceDoesNotAppear() {
        StringRedisTemplate redisTemplate = org.mockito.Mockito.mock(StringRedisTemplate.class);
        ValueOperations<String, String> valueOperations = org.mockito.Mockito.mock(ValueOperations.class);
        given(redisTemplate.opsForValue()).willReturn(valueOperations);
        given(valueOperations.setIfAbsent(anyString(), anyString(), eq(Duration.ofSeconds(10)))).willReturn(false);
        IdempotencyLockService service = service(redisTemplate);

        assertThatThrownBy(() -> service.executeOrWait(
                "course_from_ride",
                "course-from-ride:1:1001",
                Optional::<String>empty,
                () -> "created-course"
        ))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessage("같은 요청이 처리 중입니다. 잠시 후 다시 시도해 주세요.");
    }

    private IdempotencyLockService service(StringRedisTemplate redisTemplate) {
        return new IdempotencyLockService(
                redisTemplate,
                new BikeMetricsRecorder(new SimpleMeterRegistry()),
                Duration.ofSeconds(10),
                Duration.ofMillis(5),
                Duration.ofMillis(1),
                Duration.ofMillis(1),
                () -> "token-1"
        );
    }
}
