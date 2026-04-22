package com.bikeprojectminji.bikeback.auth.service;

import java.time.Duration;
import java.util.Optional;

public interface RefreshTokenStore {

    Optional<RefreshTokenSession> findBySubject(String subject);

    void save(String subject, RefreshTokenSession session, Duration ttl);
}
