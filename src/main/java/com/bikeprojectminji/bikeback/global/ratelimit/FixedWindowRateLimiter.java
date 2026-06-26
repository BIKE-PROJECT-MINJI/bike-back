package com.bikeprojectminji.bikeback.global.ratelimit;

import java.time.Duration;

public interface FixedWindowRateLimiter {

    void checkAllowed(String key, int limit, Duration ttl, String limitMessage);
}
