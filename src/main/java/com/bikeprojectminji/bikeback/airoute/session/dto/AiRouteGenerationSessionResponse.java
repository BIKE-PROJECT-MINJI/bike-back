package com.bikeprojectminji.bikeback.airoute.session.dto;

import java.util.List;

public record AiRouteGenerationSessionResponse(
        Long sessionId,
        String status,
        boolean fallbackUsed,
        String provider,
        String fallbackReason,
        List<AiRouteCandidateResponse> candidates
) {
}
