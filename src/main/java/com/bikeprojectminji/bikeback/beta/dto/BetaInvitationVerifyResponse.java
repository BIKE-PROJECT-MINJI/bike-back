package com.bikeprojectminji.bikeback.beta.dto;

import java.time.Instant;

public record BetaInvitationVerifyResponse(
        boolean valid,
        Instant expiresAt
) {
}
