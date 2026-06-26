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
import com.bikeprojectminji.bikeback.party.dto.RidePartyResponse;
import com.bikeprojectminji.bikeback.party.entity.RidePartyEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberEntity;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberRole;
import com.bikeprojectminji.bikeback.party.entity.RidePartyMemberStatus;
import com.bikeprojectminji.bikeback.party.entity.RidePartyStatus;
import com.bikeprojectminji.bikeback.party.repository.RidePartyMemberRepository;
import com.bikeprojectminji.bikeback.party.repository.RidePartyRepository;
import java.time.Clock;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RidePartyService {

    private static final int DEFAULT_CAPACITY = 6;
    private static final int MIN_CAPACITY = 2;
    private static final int MAX_CAPACITY = 50;

    private final RidePartyRepository partyRepository;
    private final RidePartyMemberRepository memberRepository;
    private final CourseRepository courseRepository;
    private final AuthService authService;
    private final Clock clock;

    public RidePartyService(
            RidePartyRepository partyRepository,
            RidePartyMemberRepository memberRepository,
            CourseRepository courseRepository,
            AuthService authService,
            Clock clock
    ) {
        this.partyRepository = partyRepository;
        this.memberRepository = memberRepository;
        this.courseRepository = courseRepository;
        this.authService = authService;
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
        List<RidePartyEntity> parties = partyRepository.findByCourseIdAndStatusOrderByCreatedAtDescIdDesc(courseId, RidePartyStatus.OPEN);
        Map<Long, List<RidePartyMemberEntity>> joinedMembersByPartyId = loadJoinedMembersByPartyId(parties);
        return new RidePartyListResponse(parties.stream()
                .map(party -> toResponse(party, joinedMembersByPartyId.getOrDefault(party.getId(), List.of()), user.getId()))
                .toList());
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

    @Transactional
    public RidePartyResponse leave(String subject, Long partyId) {
        UserEntity user = authService.findUserBySubject(subject);
        RidePartyEntity party = findOpenParty(partyId);
        RidePartyMemberEntity member = memberRepository.findByPartyIdAndUserId(party.getId(), user.getId())
                .orElseThrow(() -> new NotFoundException("참여 중인 파티가 아닙니다."));
        if (member.getRole() == RidePartyMemberRole.HOST) {
            throw new BadRequestException("호스트는 이 최소 버전에서 파티를 나갈 수 없습니다.");
        }
        if (member.getStatus() == RidePartyMemberStatus.JOINED) {
            member.leave(clock);
        }
        int joinedCount = memberRepository.countByPartyIdAndStatus(party.getId(), RidePartyMemberStatus.JOINED);
        return toResponse(party, joinedCount, false, false);
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
            throw new BadRequestException("파티 정원은 2명 이상 50명 이하여야 합니다.");
        }
        return resolved;
    }

    private RidePartyEntity findOpenParty(Long partyId) {
        if (partyId == null || partyId <= 0) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        RidePartyEntity party = partyRepository.findById(partyId)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
        if (party.getStatus() != RidePartyStatus.OPEN) {
            throw new ConflictException("참여할 수 없는 파티입니다.");
        }
        return party;
    }

    private RidePartyEntity findOpenPartyForUpdate(Long partyId) {
        if (partyId == null || partyId <= 0) {
            throw new BadRequestException("partyId가 필요합니다.");
        }
        return partyRepository.findByIdAndStatusForUpdate(partyId, RidePartyStatus.OPEN)
                .orElseThrow(() -> new NotFoundException("파티를 찾을 수 없습니다."));
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
