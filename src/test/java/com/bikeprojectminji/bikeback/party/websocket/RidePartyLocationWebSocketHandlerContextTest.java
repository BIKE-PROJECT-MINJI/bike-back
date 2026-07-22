package com.bikeprojectminji.bikeback.party.websocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.party.service.RidePartyLocationAccessService;
import com.bikeprojectminji.bikeback.party.service.RidePartyLocationService;
import com.bikeprojectminji.bikeback.party.service.RidePartySocketTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class RidePartyLocationWebSocketHandlerContextTest {

    @Test
    void createsHandlerThroughSpringApplicationContext() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(RidePartySocketTokenService.class, () -> mock(RidePartySocketTokenService.class));
            context.registerBean(RidePartyLocationService.class, () -> mock(RidePartyLocationService.class));
            context.registerBean(RidePartyLocationAccessService.class, () -> mock(RidePartyLocationAccessService.class));
            context.registerBean(RidePartySocketSessionRegistry.class, RidePartySocketSessionRegistry::new);
            context.registerBean(RidePartyDistributedStateService.class, () -> mock(RidePartyDistributedStateService.class));
            context.register(RidePartyLocationWebSocketHandler.class);
            context.register(RidePartySocketRevocationListener.class);
            context.refresh();

            assertThat(context.getBean(RidePartyLocationWebSocketHandler.class)).isNotNull();
            assertThat(context.getBean(RidePartySocketRevocationListener.class)).isNotNull();
        }
    }
}
