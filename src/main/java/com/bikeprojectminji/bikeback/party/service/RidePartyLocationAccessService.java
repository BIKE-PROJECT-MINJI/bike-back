package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyLocationAccessService {

    private final RidePartyMemberRepository memberRepository;

    public RidePartyLocationAccessService(RidePartyMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public boolean canShare(Long partyId, Long userId) {
        if (partyId == null || userId == null) {
            return false;
        }
        return memberRepository.existsLocationShareAccess(
                partyId,
                userId,
                RidePartyMemberStatus.JOINED.name(),
                RidePartyStatus.RIDING.name(),
                CourseVisibility.PUBLIC.name()
        );
    }
}
