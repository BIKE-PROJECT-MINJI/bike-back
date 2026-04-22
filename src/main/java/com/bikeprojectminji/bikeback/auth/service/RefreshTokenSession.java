package com.bikeprojectminji.bikeback.auth.service;

public record RefreshTokenSession(
        String subject,
        String tokenHash
) {
}
