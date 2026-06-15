package com.bikeprojectminji.bikeback.airoute.session;

import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanRequest;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePlanResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRoutePointResponse;
import com.bikeprojectminji.bikeback.airoute.dto.AiRouteElevationSummaryResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteCandidateResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionCreateRequest;
import com.bikeprojectminji.bikeback.airoute.session.dto.AiRouteGenerationSessionResponse;
import com.bikeprojectminji.bikeback.airoute.session.dto.PromoteAiRouteCandidateRequest;
import com.bikeprojectminji.bikeback.airoute.service.AiRoutePlannerService;
import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRouteGeometryRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRoutePointRepository;
import com.bikeprojectminji.bikeback.course.service.CourseRouteSnapshotService;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiRouteGenerationSessionService {

    private static final String SESSION_NOT_FOUND = "AI 코스 생성 세션을 찾을 수 없습니다.";

    private final AuthService authService;
    private final AiRoutePlannerService aiRoutePlannerService;
    private final AiRouteGenerationRateLimiter rateLimiter;
    private final AiRouteGenerationSessionRepository sessionRepository;
    private final AiRouteCandidateRepository candidateRepository;
    private final CourseRepository courseRepository;
    private final CourseRoutePointRepository courseRoutePointRepository;
    private final CourseRouteGeometryRepository courseRouteGeometryRepository;
    private final CourseRouteSnapshotService courseRouteSnapshotService;
    private final ObjectMapper objectMapper;

    public AiRouteGenerationSessionService(
            AuthService authService,
            AiRoutePlannerService aiRoutePlannerService,
            AiRouteGenerationRateLimiter rateLimiter,
            AiRouteGenerationSessionRepository sessionRepository,
            AiRouteCandidateRepository candidateRepository,
            CourseRepository courseRepository,
            CourseRoutePointRepository courseRoutePointRepository,
            CourseRouteGeometryRepository courseRouteGeometryRepository,
            CourseRouteSnapshotService courseRouteSnapshotService,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.aiRoutePlannerService = aiRoutePlannerService;
        this.rateLimiter = rateLimiter;
        this.sessionRepository = sessionRepository;
        this.candidateRepository = candidateRepository;
        this.courseRepository = courseRepository;
        this.courseRoutePointRepository = courseRoutePointRepository;
        this.courseRouteGeometryRepository = courseRouteGeometryRepository;
        this.courseRouteSnapshotService = courseRouteSnapshotService;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AiRouteGenerationSessionResponse createSession(String subject, AiRouteGenerationSessionCreateRequest request) {
        validateCreateRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        rateLimiter.checkAllowed(String.valueOf(user.getId()));

        AiRoutePlanResponse plan = aiRoutePlannerService.plan(subject, toPlanRequest(request));
        List<AiRoutePointResponse> routePoints = plan.routePoints() == null ? List.of() : plan.routePoints();
        if (routePoints.isEmpty()) {
            throw new BadRequestException("생성된 경로 포인트가 비어 있습니다.");
        }

        boolean fallbackUsed = !plan.aiGenerated();
        AiRouteGenerationSessionEntity session = sessionRepository.save(new AiRouteGenerationSessionEntity(
                user.getId(),
                fallbackUsed ? AiRouteGenerationSessionStatus.FALLBACK_READY : AiRouteGenerationSessionStatus.READY,
                fallbackUsed,
                fallbackUsed ? "backend-fallback" : "gemini",
                fallbackUsed ? "AI_WORKER_UNAVAILABLE" : null,
                request.textIntent()
        ));

        candidateRepository.save(toCandidate(session.getId(), plan, routePoints));
        return toResponse(session);
    }

    @Transactional(readOnly = true)
    public AiRouteGenerationSessionResponse getSession(String subject, Long sessionId) {
        UserEntity user = authService.findUserBySubject(subject);
        AiRouteGenerationSessionEntity session = findOwnedSession(sessionId, user.getId());
        return toResponse(session);
    }

    @Transactional
    public AiRoutePromotedCourseResponse promoteCandidate(
            String subject,
            Long sessionId,
            Long candidateId,
            PromoteAiRouteCandidateRequest request
    ) {
        validatePromoteRequest(request);
        UserEntity user = authService.findUserBySubject(subject);
        AiRouteGenerationSessionEntity session = findOwnedSession(sessionId, user.getId());
        if (session.getStatus() != AiRouteGenerationSessionStatus.READY
                && session.getStatus() != AiRouteGenerationSessionStatus.FALLBACK_READY) {
            throw new BadRequestException("저장 가능한 상태의 AI 코스 후보가 아닙니다.");
        }

        AiRouteCandidateEntity candidate = candidateRepository.findByIdAndSessionId(candidateId, sessionId)
                .orElseThrow(() -> new NotFoundException("AI 코스 후보를 찾을 수 없습니다."));
        if (candidate.getPromotedCourseId() != null) {
            throw new BadRequestException("이미 Course로 저장된 AI 코스 후보입니다.");
        }

        List<AiRoutePointResponse> routePoints = parseRoutePoints(candidate);
        CourseEntity course = new CourseEntity(
                request.name().trim(),
                normalizeOptionalText(request.description()),
                candidate.getDistanceKm(),
                candidate.getEstimatedDurationMin(),
                resolveNextDisplayOrder(),
                false,
                null,
                routePoints.get(0).lat(),
                routePoints.get(0).lon(),
                user.getId(),
                null,
                parseVisibility(request.visibility())
        );
        course.attachAiRouteSource(sessionId, candidateId);
        CourseEntity savedCourse = courseRepository.save(course);

        List<CourseRoutePointEntity> courseRoutePoints = toCourseRoutePoints(savedCourse.getId(), routePoints);
        courseRoutePointRepository.saveAll(courseRoutePoints);
        courseRoutePointRepository.flush();
        courseRouteGeometryRepository.refreshRouteLine(savedCourse.getId());
        courseRouteSnapshotService.evict(savedCourse.getId(), "ai_route_candidate_promoted");

        candidate.markPromoted(savedCourse.getId());
        candidateRepository.save(candidate);
        return new AiRoutePromotedCourseResponse(savedCourse.getId(), courseRoutePoints.size());
    }

    private AiRoutePlanRequest toPlanRequest(AiRouteGenerationSessionCreateRequest request) {
        return new AiRoutePlanRequest(
                request.lat(),
                request.lon(),
                request.destinationLat(),
                request.destinationLon(),
                request.destinationLabel(),
                request.rideStyle(),
                request.elevationPreference(),
                request.textIntent()
        );
    }

    private AiRouteCandidateEntity toCandidate(Long sessionId, AiRoutePlanResponse plan, List<AiRoutePointResponse> routePoints) {
        BigDecimal distanceKm = estimateDistanceKm(routePoints);
        return new AiRouteCandidateEntity(
                sessionId,
                defaultTitle(plan.summary()),
                plan.summary(),
                distanceKm,
                estimateDurationMin(distanceKm),
                plan.recommendationScore(),
                writeNullable(plan.elevationSummary()),
                routePoints.size(),
                writeRoutePoints(routePoints)
        );
    }

    private AiRouteGenerationSessionResponse toResponse(AiRouteGenerationSessionEntity session) {
        List<AiRouteCandidateResponse> candidates = candidateRepository.findBySessionIdOrderByIdAsc(session.getId())
                .stream()
                .map(candidate -> new AiRouteCandidateResponse(
                        candidate.getId(),
                        candidate.getTitle(),
                        candidate.getSummary(),
                        candidate.getDistanceKm(),
                        candidate.getEstimatedDurationMin(),
                        candidate.getRecommendationScore(),
                        readNullable(candidate.getElevationSummaryJson(), AiRouteElevationSummaryResponse.class),
                        parseRoutePoints(candidate),
                        candidate.getRoutePointCount(),
                        candidate.getPromotedCourseId()
                ))
                .toList();

        return new AiRouteGenerationSessionResponse(
                session.getId(),
                session.getStatus().name(),
                session.isFallbackUsed(),
                session.getProvider(),
                session.getFallbackReason(),
                candidates
        );
    }

    private AiRouteGenerationSessionEntity findOwnedSession(Long sessionId, Long ownerUserId) {
        return sessionRepository.findByIdAndOwnerUserId(sessionId, ownerUserId)
                .orElseThrow(() -> new NotFoundException(SESSION_NOT_FOUND));
    }

    private String writeRoutePoints(List<AiRoutePointResponse> routePoints) {
        try {
            return objectMapper.writeValueAsString(routePoints);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI 코스 후보 경로를 저장할 수 없습니다.");
        }
    }

    private String writeNullable(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI 코스 후보 정보를 저장할 수 없습니다.");
        }
    }

    private <T> T readNullable(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI 코스 후보 정보를 읽을 수 없습니다.");
        }
    }

    private List<AiRoutePointResponse> parseRoutePoints(AiRouteCandidateEntity candidate) {
        try {
            List<AiRoutePointResponse> routePoints = objectMapper.readValue(
                    candidate.getRoutePointsJson(),
                    new TypeReference<>() {
                    }
            );
            if (routePoints.isEmpty()) {
                throw new BadRequestException("AI 코스 후보 경로가 비어 있습니다.");
            }
            return routePoints;
        } catch (JsonProcessingException exception) {
            throw new BadRequestException("AI 코스 후보 경로를 읽을 수 없습니다.");
        }
    }

    private List<CourseRoutePointEntity> toCourseRoutePoints(Long courseId, List<AiRoutePointResponse> routePoints) {
        return java.util.stream.IntStream.range(0, routePoints.size())
                .mapToObj(index -> new CourseRoutePointEntity(
                        courseId,
                        index + 1,
                        routePoints.get(index).lat(),
                        routePoints.get(index).lon()
                ))
                .toList();
    }

    private BigDecimal estimateDistanceKm(List<AiRoutePointResponse> routePoints) {
        double meters = 0.0d;
        for (int index = 1; index < routePoints.size(); index++) {
            AiRoutePointResponse before = routePoints.get(index - 1);
            AiRoutePointResponse current = routePoints.get(index);
            meters += haversineMeters(
                    before.lat().doubleValue(),
                    before.lon().doubleValue(),
                    current.lat().doubleValue(),
                    current.lon().doubleValue()
            );
        }
        return BigDecimal.valueOf(meters / 1000.0d).setScale(1, RoundingMode.HALF_UP);
    }

    private double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double earthRadiusMeters = 6_371_000.0d;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2.0d) * Math.sin(dLat / 2.0d)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2.0d) * Math.sin(dLon / 2.0d);
        return earthRadiusMeters * 2.0d * Math.atan2(Math.sqrt(a), Math.sqrt(1.0d - a));
    }

    private Integer estimateDurationMin(BigDecimal distanceKm) {
        return Math.max(1, distanceKm.multiply(BigDecimal.valueOf(4)).setScale(0, RoundingMode.HALF_UP).intValue());
    }

    private int resolveNextDisplayOrder() {
        return courseRepository.findTopByOrderByDisplayOrderDescIdDesc()
                .map(course -> course.getDisplayOrder() + 1)
                .orElse(1);
    }

    private CourseVisibility parseVisibility(String visibility) {
        try {
            return CourseVisibility.valueOf(visibility.trim().toUpperCase());
        } catch (RuntimeException exception) {
            throw new BadRequestException("visibility는 PRIVATE, UNLISTED, PUBLIC 중 하나여야 합니다.");
        }
    }

    private String defaultTitle(String summary) {
        if (summary == null || summary.isBlank()) {
            return "AI 추천 코스";
        }
        return summary.length() > 160 ? summary.substring(0, 160) : summary;
    }

    private String normalizeOptionalText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private void validateCreateRequest(AiRouteGenerationSessionCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("AI 코스 생성 요청 본문이 필요합니다.");
        }
        if (request.lat() == null || request.lon() == null) {
            throw new BadRequestException("출발 좌표가 필요합니다.");
        }
    }

    private void validatePromoteRequest(PromoteAiRouteCandidateRequest request) {
        if (request == null) {
            throw new BadRequestException("코스 저장 요청 본문이 필요합니다.");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new BadRequestException("name은 비어 있을 수 없습니다.");
        }
        if (request.visibility() == null || request.visibility().isBlank()) {
            throw new BadRequestException("visibility는 비어 있을 수 없습니다.");
        }
    }
}
