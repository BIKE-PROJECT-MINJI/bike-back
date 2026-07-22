package com.bikeprojectminji.bikeback.party.websocket;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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

    @Test
    @DisplayName("동일 session의 send와 close는 registry 안정 lock으로 직렬화한다")
    void serializesConcurrentSendAndClose() throws Exception {
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);
        CountDownLatch sent = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> { sent.countDown(); return null; }).when(session).sendMessage(org.mockito.ArgumentMatchers.any());

        Thread sender = new Thread(() -> registry.send(session, new org.springframework.web.socket.TextMessage("location")));
        Thread closer = new Thread(() -> { try { sent.await(2, TimeUnit.SECONDS); registry.close(session, CloseStatus.POLICY_VIOLATION); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } });
        sender.start(); closer.start(); sender.join(); closer.join();

        verify(session).sendMessage(org.mockito.ArgumentMatchers.any());
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        org.assertj.core.api.Assertions.assertThat(registry.sessionsForParty(20L)).isEmpty();
    }

    @Test
    @DisplayName("send failure는 같은 lock에서 한 번만 close하고 후속 close를 격리한다")
    void closesFailedSendOnce() throws Exception {
        WebSocketSession session = openSession();
        registry.register(20L, 2L, session);
        org.mockito.Mockito.doThrow(new java.io.IOException("broken write"))
                .when(session).sendMessage(org.mockito.ArgumentMatchers.any());

        org.assertj.core.api.Assertions.assertThat(
                registry.send(session, new org.springframework.web.socket.TextMessage("location"))).isFalse();
        registry.close(session, CloseStatus.POLICY_VIOLATION);

        verify(session, org.mockito.Mockito.times(1)).close(CloseStatus.SERVER_ERROR);
        org.assertj.core.api.Assertions.assertThat(registry.sessionsForParty(20L)).isEmpty();
    }

    @Test
    @DisplayName("transport close 예외는 다른 registry session의 drain을 막지 않는다")
    void isolatesTransportCloseExceptions() throws Exception {
        WebSocketSession broken = openSession();
        WebSocketSession healthy = openSession();
        registry.register(20L, 2L, broken);
        registry.register(20L, 3L, healthy);
        org.mockito.Mockito.doThrow(new IllegalStateException("broken close"))
                .when(broken).close(org.mockito.ArgumentMatchers.any());

        registry.closeAll();

        verify(healthy).close(CloseStatus.POLICY_VIOLATION.withReason("distributed-bus-unavailable"));
        org.assertj.core.api.Assertions.assertThat(registry.sessionsForParty(20L)).isEmpty();
    }

    private WebSocketSession openSession() {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
