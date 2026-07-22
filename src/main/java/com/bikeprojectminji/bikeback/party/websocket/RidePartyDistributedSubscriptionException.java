package com.bikeprojectminji.bikeback.party.websocket;

public class RidePartyDistributedSubscriptionException extends RuntimeException {

    public RidePartyDistributedSubscriptionException(Throwable cause) {
        super("Party WebSocket Redis subscription is unavailable.", cause);
    }
}
