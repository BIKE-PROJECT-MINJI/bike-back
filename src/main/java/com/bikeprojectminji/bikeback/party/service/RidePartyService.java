package com.bikeprojectminji.bikeback.party.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ConflictException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.party.dto.CreateRidePartyRequest;
import com.bikeprojectminji.bikeback.party.dto.RidePartyListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyMemberListResponse;
import com.bikeprojectminji.bikeback.party.dto.RidePartyMemberResponse;
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
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyService {

    private static final int DEFAULT_CAPACITY = 5;
    private static final int MIN_CAPACITY = 1;
    private static final int MAX_CAPACITY = 10;
    private static final int PARTY_LIST_LIMIT = 50;
    private static final List<RidePartyStatus> ACTIVE_STATUSES = List.of(RidePartyStatus.OPEN, RidePartyStatus.RIDING);

    private final RidePartyRepository partyRepository;
    private final RidePartyMemberRepository memberRepository;
    private final CourseRepository courseRepository;
    private final AuthService authService;
    private final RidePartySocketTokenService socketTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public RidePartyService(
            RidePartyRepository partyRepository,
            RidePartyMemberRepository memberRepository,
            CourseRepository courseRepository,
            AuthService authService,
            RidePartySocketTokenService socketTokenService,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.partyRepository = partyRepository;
        this.memberRepository = memberRepository;
        this.courseRepository = courseRepository;
        this.authService = authService;
        this.socketTokenService = socketTokenService;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public RidePartyResponse create(String subject, CreateRidePartyRequest request) {
        UserEntity user = authService.findUserBySubject(subject);
        CourseEntity course = courseRepository.findById(requiredCourseId(request))
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        assertPartyAllowedCourse(course);
        RidePartyEntity party = partyRepository.save(new RidePartyEntity(
                course.getId(),
                user.getId(),
                resolveTitle(request.title(), course.getTitle()),
                request.scheduledStartAt(),
                resolveCapacity(request.capacity())
        ));
        memberRepository.save(new RidePartyMemberEntity(party.getId(), user.getId(), RidePartyMemberRole.HOST, clock));
        return toResponse(party, 1, true, true);
    }

    @Transactional(readOnly = true)
    public RidePartyListResponse listByCourse(String subject, Long courseId) {
        UserEntity user = authService.findUserBySubject(subject);
        if (courseId == null || courseId <= 0) {
            throw new BadRequestException("courseId가 필요합니다.");
        }
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        assertPartyAllowedCourse(course);
        List<RidePartyEntity> parties = partyRepository.findByCourseIdAndStatusInOrderByCreatedAtDescIdDesc(
                courseId,
                ACTIVE_STATUSES
        );
        Map<Long, List<RidePartyMemberEntity>> joinedMembersByPartyId = loadJoinedMembersByPartyId(parties);
        return new RidePartyListResponse(parties.stream()
                .map(party -> toResponse(party, joinedMembersByPartyId.getOrDefault(party.getId(), List.of()), user.getId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public RidePartyListResponse listAll(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        List<RidePartyEntity> parties = partyRepository.findByStatusInOrderByCreatedAtDescIdDesc(
                ACTIVE_STATUSES,
                PageRequest.of(0, PARTY_LIST_LIMIT)
        );
        return visiblePartyList(parties, user.getId());
    }

    @Transactional(readOnly = true)
    public RidePartyListResponse listMine(String subject) {
        UserEntity user = authService.findUserBySubject(subject);
        List<RidePartyEntity> parties = partyRepository.findJoinedByUserIdAndStatuses(
                user.getId(),
                ACTIVE_STATUSES,
                PageRequest.of(0, PARTY_LIST_LIMIT)
        );
        return visiblePartyList(parties, user.getId());
    }

    @Transactional
    public RidePartyResponse join(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findOpenPartyForUpdate(partyId);
        CourseEntity course = courseRepository.findById(party.getCourseId())
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        assertPartyAllowedCourse(course);
        int joinedCount = memberRepository.countByPartyIdAndStatus(party.getId(), RidePartyMemberStatus.JOINED);
        RidePartyMemberEntity member = memberRepository.findByPartyIdAndUserId(party.getId(), user.getId()).orElse(null);
        if (member != null && member.getStatus() == RidePartyMemberStatus.JOINED) {
            return toResponse(party, joinedCount, true, member.getRole() == RidePartyMemberRole.HOST);
        }
        if (joinedCount >= party.getCapacity()) {
            throw new ConflictException("파티 정원이 가득 찼습니다.");
        }
        if (member == null) {
            memberRepository.save(new RidePartyMemberEntity(party.getId(), user.getId(), RidePartyMemberRole.MEMBER, clock));
        } else {
            member.rejoin(clock);
        }
        return toResponse(party, joinedCount + 1, true, Objects.equals(party.getHostUserId(), user.getId()));
    }

    @Transactional(readOnly = true)
    public RidePartyMemberListResponse listMembers(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findActiveParty(partyId);
        assertCurrentJoinedMember(party.getId(), user.getId());
        List<RidePartyMemberResponse> members = memberRepository
                .findByPartyIdAndStatusOrderByJoinedAtAscIdAsc(party.getId(), RidePartyMemberStatus.JOINED)
                .stream()
                .map(member -> new RidePartyMemberResponse(
                        member.getUserId(),
                        member.getRole().name(),
                        member.getStatus().name(),
                        member.getJoinedAt()
                ))
                .toList();
        return new RidePartyMemberListResponse(members);
    }

    @Transactional(readOnly = true)
    public RidePartySocketTokenResponse issueSocketToken(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findActiveParty(partyId);
        assertCurrentJoinedMember(party.getId(), user.getId());
        RidePartySocketTokenService.IssuedRidePartySocketToken issued = socketTokenService.issue(party.getId(), user.getId());
        return new RidePartySocketTokenResponse(issued.token(), issued.expiresAt());
    }

    @Transactional
    public RidePartyResponse leave(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findActiveParty(partyId);
        RidePartyMemberEntity member = memberRepository.findByPartyIdAndUserId(party.getId(), user.getId())
                .orElseThrow(() -> new NotFoundException("참여 중인 파티가 아닙니다."));
        if (member.getRole() == RidePartyMemberRole.HOST) {
            throw new BadRequestException("호스트는 이 최소 버전에서 파티를 나갈 수 없습니다.");
        }
        if (member.getStatus() == RidePartyMemberStatus.JOINED) {
            member.leave(clock);
            eventPublisher.publishEvent(new RidePartyMemberLeftEvent(party.getId(), user.getId()));
        }
        int joinedCount = memberRepository.countByPartyIdAndStatus(party.getId(), RidePartyMemberStatus.JOINED);
        return toResponse(party, joinedCount, false, false);
    }

    @Transactional
    public RidePartyResponse start(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findPartyForUpdate(partyId);
        if (!Objects.equals(party.getHostUserId(), user.getId())) {
            throw new ForbiddenException("호스트만 파티 주행을 시작할 수 있습니다.");
        }
        if (party.getStatus() == RidePartyStatus.CANCELED) {
            throw new ConflictException("취소된 파티는 시작할 수 없습니다.");
        }
        CourseEntity course = courseRepository.findById(party.getCourseId())
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        assertPartyAllowedCourse(course);
        party.startRiding();
        int joinedCount = memberRepository.countByPartyIdAndStatus(party.getId(), RidePartyMemberStatus.JOINED);
        return toResponse(party, joinedCount, true, true);
    }

    private void assertPartyAllowedCourse(CourseEntity course) {
        if (course.getVisibility() != CourseVisibility.PUBLIC || course.isReportHidden()) {
            throw new ForbiddenException("공개 코스만 파티를 만들거나 참여할 수 있습니다.");
        }
    }

    private Long requiredCourseId(CreateRidePartyRequest request) {
        if (request == null) {
            throw new BadRequestException("요청 본문이 필요합니다.");
        }
        if (request.courseId() == null || request.courseId() <= 0) {
            throw new BadRequestException("courseId가 필요합니다.");
        }
        return request.courseId();
    }

    private String resolveTitle(String title, String courseTitle) {
        if (title == null || title.isBlank()) {
            return courseTitle + " 같이 타기";
        }
        String normalized = title.trim();
        if (normalized.length() > 120) {
            throw new BadRequestException("파티 제목은 120자 이하여야 합니다.");
        }
        return normalized;
    }

    private int resolveCapacity(Integer capacity) {
        int resolved = capacity == null ? DEFAULT_CAPACITY : capacity;
        if (resolved < MIN_CAPACITY || resolved > MAX_CAPACITY) {
            throw new BadRequestException("파티 정원은 1명 이상 10명 이하여야 합니다.");
        }
        return resolved;
    }

    private RidePartyEntity findActiveParty(Long partyId) {
        if (partyId == null || partyId <= 0) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        RidePartyEntity party = partyRepository.findById(partyId)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
        if (party.getStatus() == RidePartyStatus.CANCELED) {
            throw new ConflictException("참여할 수 없는 파티입니다.");
        }
        return party;
    }

    private RidePartyEntity findPartyForUpdate(Long partyId) {
        if (partyId == null || partyId <= 0) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        return partyRepository.findByIdForUpdate(partyId)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
    }

    private RidePartyEntity findOpenPartyForUpdate(Long partyId) {
        if (partyId == null || partyId <= 0) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        return partyRepository.findByIdAndStatusForUpdate(partyId, RidePartyStatus.OPEN)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
    }

    private RidePartyMemberEntity assertCurrentJoinedMember(Long partyId, Long userId) {
        RidePartyMemberEntity member = memberRepository.findByPartyIdAndUserId(partyId, userId)
                .orElseThrow(() -> new ForbiddenException("파티 참여자만 사용할 수 있습니다."));
        if (member.getStatus() != RidePartyMemberStatus.JOINED) {
            throw new ForbiddenException("파티 참여자만 사용할 수 있습니다.");
        }
        return member;
    }

    private Map<Long, List<RidePartyMemberEntity>> loadJoinedMembersByPartyId(List<RidePartyEntity> parties) {
        if (parties.isEmpty()) {
            return Collections.emptyMap();
        }
        return memberRepository.findByPartyIdInAndStatus(
                        parties.stream().map(RidePartyEntity::getId).toList(),
                        RidePartyMemberStatus.JOINED
                )
                .stream()
                .collect(Collectors.groupingBy(RidePartyMemberEntity::getPartyId));
    }

    private RidePartyListResponse visiblePartyList(List<RidePartyEntity> parties, Long currentUserId) {
        Map<Long, CourseEntity> courseById = courseRepository.findAllById(
                        parties.stream().map(RidePartyEntity::getCourseId).distinct().toList()
                )
                .stream()
                .collect(Collectors.toMap(CourseEntity::getId, Function.identity()));
        List<RidePartyEntity> visibleParties = parties.stream()
                .filter(party -> {
                    CourseEntity course = courseById.get(party.getCourseId());
                    return course != null
                            && course.getVisibility() == CourseVisibility.PUBLIC
                            && !course.isReportHidden();
                })
                .toList();
        Map<Long, List<RidePartyMemberEntity>> joinedMembersByPartyId = loadJoinedMembersByPartyId(visibleParties);
        return new RidePartyListResponse(visibleParties.stream()
                .map(party -> toResponse(
                        party,
                        joinedMembersByPartyId.getOrDefault(party.getId(), List.of()),
                        currentUserId
                ))
                .toList());
    }

    private RidePartyResponse toResponse(RidePartyEntity party, List<RidePartyMemberEntity> joinedMembers, Long currentUserId) {
        Map<Long, RidePartyMemberEntity> memberByUserId = joinedMembers.stream()
                .collect(Collectors.toMap(RidePartyMemberEntity::getUserId, Function.identity(), (left, right) -> left));
        RidePartyMemberEntity currentMember = memberByUserId.get(currentUserId);
        return toResponse(
                party,
                joinedMembers.size(),
                currentMember != null,
                currentMember != null && currentMember.getRole() == RidePartyMemberRole.HOST
        );
    }

    private RidePartyResponse toResponse(RidePartyEntity party, int joinedCount, boolean currentUserMember, boolean currentUserHost) {
        return new RidePartyResponse(
                party.getId(),
                party.getCourseId(),
                party.getHostUserId(),
                party.getTitle(),
                party.getScheduledStartAt(),
                party.getCapacity(),
                joinedCount,
                party.getStatus().name(),
                currentUserMember,
                currentUserHost
        );
    }
}
