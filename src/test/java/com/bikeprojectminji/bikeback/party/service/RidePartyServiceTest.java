package com.bikeprojectminji.bikeback.party.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.party.dto.CreateRidePartyRequest;
import com.bikeprojectminji.bikeback.party.dto.RidePartyMemberListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartySocketTokenResponse;
import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberRole;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.event.RidePartyMemberLeftEvent;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
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

    @Mock
    private RidePartySocketTokenService socketTokenService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
    @DisplayName("파티 생성 기본 정원은 정책 기준 5명이다")
    void createUsesDefaultCapacityFive() {
        RidePartyService service = createService();
        UserEntity user = user(1L);
        CourseEntity course = course(10L, CourseVisibility.PUBLIC);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(10L)).willReturn(Optional.of(course));
        given(partyRepository.save(any(RidePartyEntity.class))).willAnswer(invocation -> {
            RidePartyEntity party = invocation.getArgument(0);
            ReflectionTestUtils.setField(party, "id", 20L);
            return party;
        });

        RidePartyResponse response = service.create("1", new CreateRidePartyRequest(10L, null, OffsetDateTime.now(clock), null));

        assertThat(response.capacity()).isEqualTo(5);
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

    @Test
    @DisplayName("파티 참여자 목록은 현재 참여자만 조회할 수 있다")
    void listMembersReturnsJoinedMembersForCurrentMember() {
        RidePartyService service = createService();
        UserEntity user = user(2L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        given(authService.findUserBySubject("2")).willReturn(user);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(memberRepository.findByPartyIdAndUserId(20L, 2L))
                .willReturn(Optional.of(member(20L, 2L, RidePartyMemberRole.MEMBER)));
        given(memberRepository.findByPartyIdAndStatusOrderByJoinedAtAscIdAsc(20L, RidePartyMemberStatus.JOINED))
                .willReturn(List.of(
                        member(20L, 1L, RidePartyMemberRole.HOST),
                        member(20L, 2L, RidePartyMemberRole.MEMBER)
                ));

        RidePartyMemberListResponse response = service.listMembers("2", 20L);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0).role()).isEqualTo("HOST");
        assertThat(response.items().get(1).userId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("전체 파티 목록은 현재 공개 코스의 활성 파티만 반환한다")
    void listAllFiltersPartiesWhoseCourseIsNoLongerPublic() {
        RidePartyService service = createService();
        RidePartyEntity visibleParty = party(20L, 10L, 1L);
        RidePartyEntity hiddenParty = party(21L, 11L, 3L);
        given(authService.findUserBySubject("2")).willReturn(user(2L));
        given(partyRepository.findByStatusInOrderByCreatedAtDescIdDesc(
                any(), any(Pageable.class)
        )).willReturn(List.of(visibleParty, hiddenParty));
        given(courseRepository.findAllById(any())).willReturn(List.of(
                course(10L, CourseVisibility.PUBLIC),
                course(11L, CourseVisibility.PRIVATE)
        ));
        given(memberRepository.findByPartyIdInAndStatus(any(), any())).willReturn(List.of(
                member(20L, 1L, RidePartyMemberRole.HOST)
        ));

        RidePartyResponse response = service.listAll("2").items().get(0);

        assertThat(response.id()).isEqualTo(20L);
    }

    @Test
    @DisplayName("내 파티 목록은 현재 JOINED 상태인 활성 파티만 반환한다")
    void listMineReturnsOnlyJoinedParties() {
        RidePartyService service = createService();
        RidePartyEntity party = party(20L, 10L, 1L);
        given(authService.findUserBySubject("2")).willReturn(user(2L));
        given(partyRepository.findJoinedByUserIdAndStatuses(
                org.mockito.ArgumentMatchers.eq(2L), any(), any(Pageable.class)
        )).willReturn(List.of(party));
        given(courseRepository.findAllById(any())).willReturn(List.of(course(10L, CourseVisibility.PUBLIC)));
        given(memberRepository.findByPartyIdInAndStatus(any(), any())).willReturn(List.of(
                member(20L, 1L, RidePartyMemberRole.HOST),
                member(20L, 2L, RidePartyMemberRole.MEMBER)
        ));

        RidePartyResponse response = service.listMine("2").items().get(0);

        assertThat(response.currentUserMember()).isTrue();
        assertThat(response.currentUserHost()).isFalse();
    }

    @Test
    @DisplayName("파티 socket token은 현재 참여자에게만 발급한다")
    void issueSocketTokenRequiresJoinedMember() {
        RidePartyService service = createService();
        UserEntity user = user(2L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        OffsetDateTime expiresAt = OffsetDateTime.now(clock).plusMinutes(5);
        given(authService.findUserBySubject("2")).willReturn(user);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(memberRepository.findByPartyIdAndUserId(20L, 2L))
                .willReturn(Optional.of(member(20L, 2L, RidePartyMemberRole.MEMBER)));
        given(socketTokenService.issue(20L, 2L))
                .willReturn(new RidePartySocketTokenService.IssuedRidePartySocketToken("socket-token", expiresAt));

        RidePartySocketTokenResponse response = service.issueSocketToken("2", 20L);

        assertThat(response.socketToken()).isEqualTo("socket-token");
        assertThat(response.expiresAt()).isEqualTo(expiresAt);
    }

    @Test
    @DisplayName("파티 탈퇴는 커밋 뒤 소켓을 닫기 위한 이벤트를 발행한다")
    void leavePublishesMemberSocketRevocationEvent() {
        RidePartyService service = createService();
        UserEntity user = user(2L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        RidePartyMemberEntity member = member(20L, 2L, RidePartyMemberRole.MEMBER);
        given(authService.findUserBySubject("2")).willReturn(user);
        given(partyRepository.findById(20L)).willReturn(Optional.of(party));
        given(memberRepository.findByPartyIdAndUserId(20L, 2L)).willReturn(Optional.of(member));
        given(memberRepository.countByPartyIdAndStatus(20L, RidePartyMemberStatus.JOINED)).willReturn(1);

        service.leave("2", 20L);

        verify(eventPublisher).publishEvent(new RidePartyMemberLeftEvent(20L, 2L));
        assertThat(member.getStatus()).isEqualTo(RidePartyMemberStatus.LEFT);
    }

    @Test
    @DisplayName("호스트만 OPEN 파티를 RIDING으로 시작한다")
    void startTransitionsOpenPartyForHost() {
        RidePartyService service = createService();
        UserEntity host = user(1L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        given(authService.findUserBySubject("1")).willReturn(host);
        given(partyRepository.findByIdForUpdate(20L)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course(10L, CourseVisibility.PUBLIC)));
        given(memberRepository.countByPartyIdAndStatus(20L, RidePartyMemberStatus.JOINED)).willReturn(2);

        RidePartyResponse response = service.start("1", 20L);

        assertThat(response.status()).isEqualTo("RIDING");
        assertThat(party.getStatus()).isEqualTo(RidePartyStatus.RIDING);
    }

    @Test
    @DisplayName("일반 멤버는 파티 시작 상태를 변경할 수 없다")
    void startRejectsNonHost() {
        RidePartyService service = createService();
        UserEntity member = user(2L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        given(authService.findUserBySubject("2")).willReturn(member);
        given(partyRepository.findByIdForUpdate(20L)).willReturn(Optional.of(party));

        assertThatThrownBy(() -> service.start("2", 20L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("호스트만 파티 주행을 시작할 수 있습니다.");
    }

    @Test
    @DisplayName("파티 시작은 신고 숨김 또는 비공개로 바뀐 코스를 다시 거부한다")
    void startRejectsCourseThatIsNoLongerPublic() {
        RidePartyService service = createService();
        UserEntity host = user(1L);
        RidePartyEntity party = new RidePartyEntity(10L, 1L, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", 20L);
        given(authService.findUserBySubject("1")).willReturn(host);
        given(partyRepository.findByIdForUpdate(20L)).willReturn(Optional.of(party));
        given(courseRepository.findById(10L)).willReturn(Optional.of(course(10L, CourseVisibility.PRIVATE)));

        assertThatThrownBy(() -> service.start("1", 20L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("공개 코스만 파티를 만들거나 참여할 수 있습니다.");
    }

    private RidePartyService createService() {
        return new RidePartyService(
                partyRepository,
                memberRepository,
                courseRepository,
                authService,
                socketTokenService,
                eventPublisher,
                clock
        );
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

    private RidePartyMemberEntity member(Long partyId, Long userId, RidePartyMemberRole role) {
        return new RidePartyMemberEntity(partyId, userId, role, clock);
    }

    private RidePartyEntity party(Long id, Long courseId, Long hostUserId) {
        RidePartyEntity party = new RidePartyEntity(courseId, hostUserId, "공개 파티", OffsetDateTime.now(clock), 4);
        ReflectionTestUtils.setField(party, "id", id);
        return party;
    }
}
