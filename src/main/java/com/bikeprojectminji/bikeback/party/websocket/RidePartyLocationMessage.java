package com.bikeprojectminji.bikeback.party.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.Duration;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.validation.CoordinateValidator;

public record RidePartyLocationMessage(
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal accuracyM,
        BigDecimal speedMps,
        BigDecimal bearingDeg,
        OffsetDateTime capturedAt
) {
    public void validate() {
        CoordinateValidator.validateLatLon("latitude", latitude, "longitude", longitude);
        validateNonNegative("accuracyM", accuracyM);
        validateNonNegative("speedMps", speedMps);
        if (bearingDeg != null && (bearingDeg.signum() < 0 || bearingDeg.compareTo(BigDecimal.valueOf(360)) >= 0)) {
            throw new BadRequestException("bearingDeg는 0 이상 360 미만이어야 합니다.");
        }
    }

    public void validateCapturedAt(OffsetDateTime now) {
        if (capturedAt == null || capturedAt.isBefore(now.minus(Duration.ofMinutes(5))) || capturedAt.isAfter(now.plusMinutes(1))) {
            throw new BadRequestException("capturedAt 범위가 올바르지 않습니다.");
        }
    }

    private static void validateNonNegative(String field, BigDecimal value) {
        if (value != null && value.signum() < 0) {
            throw new BadRequestException(field + "은 0 이상이어야 합니다.");
        }
    }
}
