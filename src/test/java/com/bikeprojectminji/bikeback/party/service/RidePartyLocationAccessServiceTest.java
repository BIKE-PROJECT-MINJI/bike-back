package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
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
    private final CourseRepository courseRepository = mock(CourseRepository.class);
    private final RidePartyLocationAccessService service = new RidePartyLocationAccessService(
            partyRepository,
            memberRepository,
            courseRepository
    );

    @Test
    @DisplayName("RIDING 파티의 JOINED 멤버는 공개 코스에서 위치를 공유할 수 있다")
    void allowsJoinedMemberOfRidingPartyWithPublicCourse() {
        RidePartyEntity party = party();
        party.startRiding();
        RidePartyMemberEntity member = new RidePartyMemberEntity(20L, 2L, RidePartyMemberRole.MEMBER, Clock.systemUTC());
        CourseEntity course = publicCourse(false);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course));
        given(memberRepository.findByPartyIdAndUserId(20L, 2L)).willReturn(Optional.of(member));

        assertThat(service.canShare(20L, 2L)).isTrue();
    }

    @Test
    @DisplayName("OPEN 파티는 주행 시작 전 위치를 공유할 수 없다")
    void rejectsMemberBeforePartyStartsRiding() {
        given(partyRepository.findById(20L)).willReturn(Optional.of(party()));

        assertThat(service.canShare(20L, 2L)).isFalse();
    }

    @Test
    @DisplayName("신고 숨김 코스의 기존 파티는 위치를 공유할 수 없다")
    void rejectsMemberWhenCourseBecomesReportHidden() {
        RidePartyEntity party = party();
        party.startRiding();
        CourseEntity course = publicCourse(true);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course));

        assertThat(service.canShare(20L, 2L)).isFalse();
    }

    @Test
    @DisplayName("비공개 코스의 기존 파티는 위치를 공유할 수 없다")
    void rejectsMemberWhenCourseBecomesPrivate() {
        RidePartyEntity party = party();
        party.startRiding();
        CourseEntity course = mock(CourseEntity.class);
        given(course.getVisibility()).willReturn(CourseVisibility.PRIVATE);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course));

        assertThat(service.canShare(20L, 2L)).isFalse();
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

    private CourseEntity publicCourse(boolean reportHidden) {
        CourseEntity course = mock(CourseEntity.class);
        given(course.getVisibility()).willReturn(CourseVisibility.PUBLIC);
        given(course.isReportHidden()).willReturn(reportHidden);
        return course;
    }
}
