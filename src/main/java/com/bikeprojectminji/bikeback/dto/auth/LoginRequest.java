package com.bikeprojectminji.bikeback.dto.auth;

public record LoginRequest(
        String email,
        String password
) {
}
