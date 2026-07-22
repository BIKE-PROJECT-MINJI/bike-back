package com.bikeprojectminji.bikeback.party.websocket;

public interface RidePartyDistributedEventPublisher {

    void publish(RidePartyDistributedEvent event);

    static RidePartyDistributedEventPublisher noOp() {
        return event -> { };
    }
}
