package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.party.entity.RidePartyLocationPointEntity;
import com.bikeprojectminji.bikeback.party.repository.RidePartyLocationPointRepository;
import com.bikeprojectminji.bikeback.party.websocket.RidePartyLocationMessage;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyLocationService {

    private final RidePartyLocationPointRepository locationPointRepository;
    private final Clock clock;

    public RidePartyLocationService(RidePartyLocationPointRepository locationPointRepository, Clock clock) {
        this.locationPointRepository = locationPointRepository;
        this.clock = clock;
    }

    @Transactional
    public void saveLocation(Long partyId, Long userId, RidePartyLocationMessage location) {
        locationPointRepository.save(RidePartyLocationPointEntity.create(
                partyId,
                userId,
                location.latitude(),
                location.longitude(),
                location.accuracyM(),
                location.speedMps(),
                location.bearingDeg(),
                location.capturedAt(),
                clock
        ));
    }

    @Transactional
    public int deleteExpiredLocationPoints() {
        return locationPointRepository.deleteByCreatedAtBefore(OffsetDateTime.now(clock).minusHours(24));
    }
}
