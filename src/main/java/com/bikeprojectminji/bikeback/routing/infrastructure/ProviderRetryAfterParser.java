package com.bikeprojectminji.bikeback.routing.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpHeaders;

final class ProviderRetryAfterParser {

    private ProviderRetryAfterParser() {
    }

    static int secondsOrDefault(HttpHeaders headers, int defaultSeconds) {
        return secondsOrDefault(headers, defaultSeconds, Clock.systemUTC());
    }

    static int secondsOrDefault(HttpHeaders headers, int defaultSeconds, Clock clock) {
        int normalizedDefault = Math.max(1, defaultSeconds);
        if (headers == null) {
            return normalizedDefault;
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return normalizedDefault;
        }
        try {
            return Math.max(1, Integer.parseInt(value.strip()));
        } catch (NumberFormatException ignored) {
            return secondsUntilHttpDate(value, normalizedDefault, clock);
        }
    }

    private static int secondsUntilHttpDate(String value, int defaultSeconds, Clock clock) {
        try {
            ZonedDateTime retryAt = ZonedDateTime.parse(value.strip(), DateTimeFormatter.RFC_1123_DATE_TIME);
            long seconds = Duration.between(clock.instant(), retryAt.toInstant()).getSeconds();
            return (int) Math.min(Integer.MAX_VALUE, Math.max(1, seconds));
        } catch (DateTimeParseException exception) {
            return defaultSeconds;
        }
    }
}
