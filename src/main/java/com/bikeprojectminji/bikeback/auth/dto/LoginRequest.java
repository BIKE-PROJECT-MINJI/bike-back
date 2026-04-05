package com.bikeprojectminji.bikeback.auth.dto;

public record LoginRequest(
        String email,
        String password
) {
}
