package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RidePartyLocationAccessServiceTest {

    private final RidePartyMemberRepository memberRepository = mock(RidePartyMemberRepository.class);
    private final RidePartyLocationAccessService service = new RidePartyLocationAccessService(memberRepository);

    @Test
    @DisplayName("위치 공유 권한은 멤버·파티·코스 조건을 단일 조회로 확인한다")
    void checksLocationShareAccessWithOneRepositoryQuery() {
        given(memberRepository.existsLocationShareAccess(
                20L,
                2L,
                RidePartyMemberStatus.JOINED.name(),
                RidePartyStatus.RIDING.name(),
                CourseVisibility.PUBLIC.name()
        )).willReturn(true);

        assertThat(service.canShare(20L, 2L)).isTrue();
        verify(memberRepository).existsLocationShareAccess(
                20L,
                2L,
                RidePartyMemberStatus.JOINED.name(),
                RidePartyStatus.RIDING.name(),
                CourseVisibility.PUBLIC.name()
        );
    }

    @Test
    @DisplayName("partyId 또는 userId가 없으면 DB를 조회하지 않는다")
    void rejectsMissingIdentifiersWithoutQuery() {
        assertThat(service.canShare(null, 2L)).isFalse();
        assertThat(service.canShare(20L, null)).isFalse();

        verifyNoInteractions(memberRepository);
    }
}
