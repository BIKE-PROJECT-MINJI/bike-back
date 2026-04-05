package com.bikeprojectminji.bikeback.location.service;

import java.util.Optional;

public interface RecentLocationCacheStore {

    Optional<RecentLocationSnapshot> find(String subject);

    void save(String subject, RecentLocationSnapshot snapshot);
}
