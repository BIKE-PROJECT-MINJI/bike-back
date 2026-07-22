package com.bikeprojectminji.bikeback.party.websocket;

public class RidePartyDistributedPublishException extends RuntimeException {

    public RidePartyDistributedPublishException(String eventType, Throwable cause) {
        super("Party WebSocket distributed publish failed for " + eventType + ".", cause);
    }
}
