package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
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
    private final CourseRepository courseRepository;

    public RidePartyLocationAccessService(
            RidePartyRepository partyRepository,
            RidePartyMemberRepository memberRepository,
            CourseRepository courseRepository
    ) {
        this.partyRepository = partyRepository;
        this.memberRepository = memberRepository;
        this.courseRepository = courseRepository;
    }

    @Transactional(readOnly = true)
    public boolean canShare(Long partyId, Long userId) {
        if (partyId == null || userId == null) {
            return false;
        }
        var party = partyRepository.findById(partyId).orElse(null);
        if (party == null || party.getStatus() != RidePartyStatus.RIDING) {
            return false;
        }
        boolean shareableCourse = courseRepository.findById(party.getCourseId())
                .map(course -> course.getVisibility() == CourseVisibility.PUBLIC && !course.isReportHidden())
                .orElse(false);
        if (!shareableCourse) {
            return false;
        }
        return memberRepository.findByPartyIdAndUserId(partyId, userId)
                .map(member -> member.getStatus() == RidePartyMemberStatus.JOINED)
                .orElse(false);
    }
}
