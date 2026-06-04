package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class CourseAccessPolicyTest {

    private final CourseAccessPolicy courseAccessPolicy = new CourseAccessPolicy();

    @Test
    @DisplayName("PUBLIC 코스는 로그인 사용자 없이도 읽을 수 있다")
    void publicCourseAllowsAnonymousRead() {
        assertThatCode(() -> courseAccessPolicy.assertReadable(course(CourseVisibility.PUBLIC, 1L, null), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("UNLISTED 코스는 owner 또는 shareToken이 맞을 때만 읽을 수 있다")
    void unlistedCourseAllowsOwnerOrShareToken() {
        CourseEntity course = course(CourseVisibility.UNLISTED, 1L, "share-token");

        assertThatCode(() -> courseAccessPolicy.assertReadable(course, user(1L), null))
                .doesNotThrowAnyException();
        assertThatCode(() -> courseAccessPolicy.assertReadable(course, null, "share-token"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> courseAccessPolicy.assertReadable(course, user(2L), "wrong-token"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스에 접근할 권한이 없습니다.");
    }

    @Test
    @DisplayName("PRIVATE 코스는 owner만 읽을 수 있다")
    void privateCourseAllowsOnlyOwner() {
        CourseEntity course = course(CourseVisibility.PRIVATE, 1L, null);

        assertThatCode(() -> courseAccessPolicy.assertReadable(course, user(1L), null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> courseAccessPolicy.assertReadable(course, user(2L), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스는 공개되지 않았습니다.");
    }

    private CourseEntity course(CourseVisibility visibility, Long ownerUserId, String shareToken) {
        CourseEntity course = new CourseEntity(
                "테스트 코스",
                "설명",
                BigDecimal.ONE,
                10,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                ownerUserId,
                null,
                visibility
        );
        course.updateShareToken(shareToken);
        return course;
    }

    private UserEntity user(Long userId) {
        UserEntity user = new UserEntity("external-" + userId, null, null, "rider", null);
        ReflectionTestUtils.setField(user, "id", userId);
        return user;
    }
}
