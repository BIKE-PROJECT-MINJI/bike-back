package com.bikeprojectminji.bikeback.party.websocket;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class RidePartySocketAccessSweep {
    private final RidePartyDistributedStateService stateService;
    RidePartySocketAccessSweep(RidePartyDistributedStateService stateService) { this.stateService = stateService; }
    @Scheduled(fixedDelay = 30_000)
    void sweep() { stateService.revalidateAllSessionsOrDrain(); }
}
