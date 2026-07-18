package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.party.event.RidePartyCanceledEvent;
import com.bikeprojectminji.bikeback.party.event.RidePartyMemberLeftEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RidePartySocketRevocationListener {

    private final RidePartySocketSessionRegistry sessionRegistry;

    public RidePartySocketRevocationListener(RidePartySocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberLeft(RidePartyMemberLeftEvent event) {
        sessionRegistry.closeMember(event.partyId(), event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartyCanceled(RidePartyCanceledEvent event) {
        sessionRegistry.closeParty(event.partyId());
    }
}
