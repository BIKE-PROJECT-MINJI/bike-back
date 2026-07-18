package com.bikeprojectminji.bikeback.party.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RidePartySocketSessionRegistryTest {

    private final RidePartySocketSessionRegistry registry = new RidePartySocketSessionRegistry();

    @Test
    @DisplayName("탈퇴한 멤버의 소켓은 1008 member-left 사유로 닫는다")
    void closesMemberWithMemberLeftReason() throws Exception {
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);

        registry.closeMember(20L, 2L);

        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("member-left"));
    }

    @Test
    @DisplayName("취소된 파티의 소켓은 1008 party-canceled 사유로 닫는다")
    void closesPartyWithPartyCanceledReason() throws Exception {
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);

        registry.closeParty(20L);

        verify(session).close(CloseStatus.POLICY_VIOLATION.withReason("party-canceled"));
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
