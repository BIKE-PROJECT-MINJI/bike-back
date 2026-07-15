package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberRole;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class RidePartyLocationAccessServiceTest {

    private final RidePartyRepository partyRepository = mock(RidePartyRepository.class);
    private final RidePartyMemberRepository memberRepository = mock(RidePartyMemberRepository.class);
    private final RidePartyLocationAccessService service = new RidePartyLocationAccessService(partyRepository, memberRepository);

    @Test
    @DisplayName("OPEN 파티의 JOINED 멤버만 위치를 공유할 수 있다")
    void allowsJoinedMemberOfActiveParty() {
        RidePartyEntity party = party();
        RidePartyMemberEntity member = new RidePartyMemberEntity(20L, 2L, RidePartyMemberRole.MEMBER, Clock.systemUTC());
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(memberRepository.findByPartyIdAndUserId(20L, 2L)).willReturn(Optional.of(member));

        assertThat(service.canShare(20L, 2L)).isTrue();
    }

    @Test
    @DisplayName("취소된 파티는 기존 멤버라도 위치를 공유할 수 없다")
    void rejectsMemberOfCanceledParty() {
        RidePartyEntity party = party();
        party.cancelByReport();
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));

        assertThat(service.canShare(20L, 2L)).isFalse();
    }

    private RidePartyEntity party() {
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        return party;
    }
}
