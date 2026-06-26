package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.beta.service.BetaAccessPolicy;
import com.bikeprojectminji.bikeback.course.dto.CoursePublicationResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CoursePublicationEntity;
import com.bikeprojectminji.bikeback.course.entity.CoursePublicationStatus;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CoursePublicationRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CoursePublicationServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-06-18T00:00:00Z"),
            ZoneOffset.UTC
    );

    @Mock
    private AuthService authService;

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private CoursePublicationRepository coursePublicationRepository;

    private CoursePublicationService coursePublicationService;

    @BeforeEach
    void setUp() {
        coursePublicationService = new CoursePublicationService(
                authService,
                courseRepository,
                coursePublicationRepository,
                new BetaAccessPolicy(),
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("beta 권한이 있는 소유자는 본인 코스를 공개 게시할 수 있다")
    void publishCourseCreatesActivePublicationForOwnerWithBetaAccess() {
        UserEntity user = user(1L, true);
        CourseEntity course = course(2001L, 1L);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course));
        given(coursePublicationRepository.findByCourseId(2001L)).willReturn(Optional.empty());
        given(coursePublicationRepository.save(org.mockito.ArgumentMatchers.any(CoursePublicationEntity.class)))
                .willAnswer(invocation -> {
                    CoursePublicationEntity publication = invocation.getArgument(0);
                    ReflectionTestUtils.setField(publication, "id", 3001L);
                    return publication;
                });

        CoursePublicationResponse response = coursePublicationService.publishCourse("1", 2001L);

        assertThat(response.publicationId()).isEqualTo(3001L);
        assertThat(response.courseId()).isEqualTo(2001L);
        assertThat(response.ownerUserId()).isEqualTo(1L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        assertThat(response.publishedAt()).isNotNull();
        assertThat(response.unpublishedAt()).isNull();
    }

    @Test
    @DisplayName("이미 공개된 코스의 공개 요청은 기존 active publication을 반환한다")
    void publishCourseReturnsExistingActivePublicationWhenAlreadyPublished() {
        UserEntity user = user(1L, true);
        CourseEntity course = course(2001L, 1L);
        CoursePublicationEntity publication = publication(3001L, 2001L, 1L, CoursePublicationStatus.ACTIVE);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course));
        given(coursePublicationRepository.findByCourseId(2001L)).willReturn(Optional.of(publication));

        CoursePublicationResponse response = coursePublicationService.publishCourse("1", 2001L);

        assertThat(response.publicationId()).isEqualTo(3001L);
        assertThat(response.status()).isEqualTo("ACTIVE");
        verify(coursePublicationRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("소유자는 공개 게시를 비공개 상태로 전환할 수 있다")
    void unpublishCourseMarksActivePublicationInactiveForOwnerWithBetaAccess() {
        UserEntity user = user(1L, true);
        CourseEntity course = course(2001L, 1L);
        CoursePublicationEntity publication = publication(3001L, 2001L, 1L, CoursePublicationStatus.ACTIVE);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course));
        given(coursePublicationRepository.findByCourseId(2001L)).willReturn(Optional.of(publication));

        CoursePublicationResponse response = coursePublicationService.unpublishCourse("1", 2001L);

        assertThat(response.publicationId()).isEqualTo(3001L);
        assertThat(response.status()).isEqualTo("INACTIVE");
        assertThat(response.unpublishedAt()).isNotNull();
    }

    @Test
    @DisplayName("beta 권한이 없는 사용자는 코스를 공개할 수 없다")
    void publishCourseRejectsUserWithoutBetaAccess() {
        given(authService.findUserBySubject("1")).willReturn(user(1L, false));

        assertThatThrownBy(() -> coursePublicationService.publishCourse("1", 2001L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("베타 초대 권한이 필요합니다.");

        verify(courseRepository, never()).findById(2001L);
    }

    @Test
    @DisplayName("타인 코스 공개 전환은 거부한다")
    void publishCourseRejectsDifferentOwner() {
        UserEntity user = user(1L, true);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(2001L)).willReturn(Optional.of(course(2001L, 2L)));

        assertThatThrownBy(() -> coursePublicationService.publishCourse("1", 2001L))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스를 수정할 권한이 없습니다.");
    }

    @Test
    @DisplayName("없는 코스 공개 전환은 404로 거부한다")
    void publishCourseRejectsMissingCourse() {
        UserEntity user = user(1L, true);
        given(authService.findUserBySubject("1")).willReturn(user);
        given(courseRepository.findById(9999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> coursePublicationService.publishCourse("1", 9999L))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("코스를 찾을 수 없습니다.");
    }

    private UserEntity user(Long id, boolean betaAccessGranted) {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", id);
        if (betaAccessGranted) {
            user.grantBetaAccess();
        }
        return user;
    }

    private CourseEntity course(Long id, Long ownerUserId) {
        CourseEntity course = new CourseEntity(
                "한강 코스",
                "설명",
                BigDecimal.valueOf(18.3),
                60,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                ownerUserId,
                CourseVisibility.PRIVATE
        );
        ReflectionTestUtils.setField(course, "id", id);
        return course;
    }

    private CoursePublicationEntity publication(
            Long id,
            Long courseId,
            Long ownerUserId,
            CoursePublicationStatus status
    ) {
        CoursePublicationEntity publication = new CoursePublicationEntity(courseId, ownerUserId, FIXED_CLOCK);
        ReflectionTestUtils.setField(publication, "id", id);
        if (status == CoursePublicationStatus.INACTIVE) {
            publication.unpublish(FIXED_CLOCK);
        }
        return publication;
    }
}
