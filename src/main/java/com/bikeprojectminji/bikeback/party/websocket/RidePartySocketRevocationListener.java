package com.bikeprojectminji.bikeback.party.websocket;

import com.bikeprojectminji.bikeback.party.event.RidePartyCanceledEvent;
import com.bikeprojectminji.bikeback.party.event.RidePartyMemberLeftEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class RidePartySocketRevocationListener {

    private final RidePartySocketSessionRegistry sessionRegistry;
    private final RidePartyDistributedStateService distributedStateService;

    @Autowired
    public RidePartySocketRevocationListener(
            RidePartySocketSessionRegistry sessionRegistry,
            RidePartyDistributedStateService distributedStateService
    ) {
        this.sessionRegistry = sessionRegistry;
        this.distributedStateService = distributedStateService;
    }

    RidePartySocketRevocationListener(RidePartySocketSessionRegistry sessionRegistry) {
        this(sessionRegistry, null);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMemberLeft(RidePartyMemberLeftEvent event) {
        if (distributedStateService != null) {
            distributedStateService.publishMemberRevoked(event.partyId(), event.userId());
        } else {
            sessionRegistry.closeMember(event.partyId(), event.userId());
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPartyCanceled(RidePartyCanceledEvent event) {
        if (distributedStateService != null) {
            distributedStateService.publishPartyCanceled(event.partyId());
        } else {
            sessionRegistry.closeParty(event.partyId());
        }
    }
}
