package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.party.dto.CreateRidePartyRequest;
import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RidePartyServiceTest {

    @Mock
    private RidePartyRepository partyRepository;

    @Mock
    private RidePartyMemberRepository memberRepository;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private AuthService authService;

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-26T00:00:00Z"), ZoneOffset.UTC);

    @Test
    @DisplayName("파티 생성은 PUBLIC이 아닌 코스를 거부한다")
    void createRejectsPrivateCourse() {
        RidePartyService service = createService();
        UserEntity user = user(1L);
        CourseEntity course = course(10L, CourseVisibility.PRIVATE);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(10L)).willReturn(Optional.of(course));

        assertThatThrownBy(() -> service.create("1", new CreateRidePartyRequest(10L, "비공개 파티", OffsetDateTime.now(clock), 4)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("공개 코스만 파티를 만들거나 참여할 수 있습니다.");
    }

    @Test
    @DisplayName("파티 참여는 party row를 잠근 뒤 정원을 확인한다")
    void joinUsesLockedPartyBeforeCapacityCheck() {
        RidePartyService service = createService();
        UserEntity user = user(2L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        given(authService.findUserBySubject("2")).willReturn(user);
        given(partyRepository.findByIdAndStatusForUpdate(20L, RidePartyStatus.OPEN)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course(10L, CourseVisibility.PUBLIC)));
        given(memberRepository.countByPartyIdAndStatus(20L, RidePartyMemberStatus.JOINED)).willReturn(1);
        given(memberRepository.findByPartyIdAndUserId(20L, 2L)).willReturn(Optional.empty());

        service.join("2", 20L);

        verify(partyRepository).findByIdAndStatusForUpdate(20L, RidePartyStatus.OPEN);
    }

    private RidePartyService createService() {
        return new RidePartyService(partyRepository, memberRepository, courseRepository, authService, clock);
    }

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity("external-" + id, "user" + id + "@example.com", "hash", "user" + id, null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private CourseEntity course(Long id, CourseVisibility visibility) {
        CourseEntity course = new CourseEntity(
                "테스트 코스",
                "설명",
                BigDecimal.valueOf(3.2),
                20,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5),
                BigDecimal.valueOf(127.0),
                1L,
                visibility
        );
        ReflectionTestUtils.setField(course, "id", id);
        return course;
    }
}
