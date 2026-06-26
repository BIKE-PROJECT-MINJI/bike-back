package com.bikeprojectminji.bikeback.global.ratelimit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RateLimitKeys {

    private static final String PREFIX = "bike:rate-limit";

    private RateLimitKeys() {
    }

    public static String hashed(String namespace, String subject) {
        return PREFIX + ":" + namespace + ":" + sha256(normalize(subject));
    }

    private static String normalize(String subject) {
        if (subject == null || subject.isBlank()) {
            return "UNKNOWN";
        }
        return subject.trim();
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
        }
    }
}
