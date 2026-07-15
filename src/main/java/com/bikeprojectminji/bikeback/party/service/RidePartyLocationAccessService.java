package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyLocationAccessService {

    private final RidePartyRepository partyRepository;
    private final RidePartyMemberRepository memberRepository;

    public RidePartyLocationAccessService(
            RidePartyRepository partyRepository,
            RidePartyMemberRepository memberRepository
    ) {
        this.partyRepository = partyRepository;
        this.memberRepository = memberRepository;
    }

    @Transactional(readOnly = true)
    public boolean canShare(Long partyId, Long userId) {
        if (partyId == null || userId == null) {
            return false;
        }
        boolean activeParty = partyRepository.findById(partyId)
                .map(party -> party.getStatus() == RidePartyStatus.OPEN || party.getStatus() == RidePartyStatus.RIDING)
                .orElse(false);
        if (!activeParty) {
            return false;
        }
        return memberRepository.findByPartyIdAndUserId(partyId, userId)
                .map(member -> member.getStatus() == RidePartyMemberStatus.JOINED)
                .orElse(false);
    }
}
