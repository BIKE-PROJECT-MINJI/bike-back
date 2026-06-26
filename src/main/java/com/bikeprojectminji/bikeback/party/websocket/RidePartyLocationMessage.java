package com.bikeprojectminji.bikeback.party.websocket;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record RidePartyLocationMessage(
        BigDecimal latitude,
        BigDecimal longitude,
        BigDecimal accuracyM,
        BigDecimal speedMps,
        BigDecimal bearingDeg,
        OffsetDateTime capturedAt
) {
}
