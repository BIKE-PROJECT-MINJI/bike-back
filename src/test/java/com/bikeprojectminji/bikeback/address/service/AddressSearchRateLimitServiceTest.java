package com.bikeprojectminji.bikeback.address.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.global.exception.TooManyRequestsException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AddressSearchRateLimitServiceTest {

    @Test
    @DisplayName("주소 검색 quota는 IP 기준으로 1분 제한을 초과하면 429 예외를 던진다")
    void checkAllowedRejectsRequestsOverPerMinuteLimit() {
        AddressSearchRateLimitService rateLimitService = new AddressSearchRateLimitService(
                Clock.fixed(Instant.parse("2026-06-26T05:00:00Z"), ZoneOffset.UTC),
                2,
                10
        );

        rateLimitService.checkAllowed("127.0.0.1");
        rateLimitService.checkAllowed("127.0.0.1");

        assertThatThrownBy(() -> rateLimitService.checkAllowed("127.0.0.1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("주소 검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("주소 검색 quota는 IP 기준으로 하루 제한을 초과하면 429 예외를 던진다")
    void checkAllowedRejectsRequestsOverDailyLimit() {
        AddressSearchRateLimitService rateLimitService = new AddressSearchRateLimitService(
                Clock.fixed(Instant.parse("2026-06-26T05:00:00Z"), ZoneOffset.UTC),
                10,
                2
        );

        rateLimitService.checkAllowed("127.0.0.1");
        rateLimitService.checkAllowed("127.0.0.1");

        assertThatThrownBy(() -> rateLimitService.checkAllowed("127.0.0.1"))
                .isInstanceOf(TooManyRequestsException.class)
                .hasMessage("주소 검색 요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
    }
}
