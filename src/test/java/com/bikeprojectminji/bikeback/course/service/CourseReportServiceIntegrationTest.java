package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.course.dto.CourseReportResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseReportReason;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseReportRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class CourseReportServiceIntegrationTest {

    private final CourseReportService courseReportService;
    private final CourseRepository courseRepository;
    private final CourseReportRepository courseReportRepository;
    private final UserRepository userRepository;

    @Autowired
    CourseReportServiceIntegrationTest(
            CourseReportService courseReportService,
            CourseRepository courseRepository,
            CourseReportRepository courseReportRepository,
            UserRepository userRepository
    ) {
        this.courseReportService = courseReportService;
        this.courseRepository = courseRepository;
        this.courseReportRepository = courseReportRepository;
        this.userRepository = userRepository;
    }

    @BeforeEach
    void setUp() {
        courseReportRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("사유지 또는 통행 불가 신고는 1회만으로 코스를 임시 숨김 처리한다")
    void highRiskReportHidesCourseImmediately() {
        CourseEntity course = courseRepository.save(publicCourse(owner().getId()));
        UserEntity reporter = userRepository.save(new UserEntity("reporter-a", "reporter-a@example.test", "hash", "신고자A", null));

        CourseReportResponse response = courseReportService.reportCourse(
                String.valueOf(reporter.getId()),
                course.getId(),
                CourseReportReason.PRIVATE_PROPERTY_OR_CLOSED_ROAD
        );

        CourseEntity hidden = courseRepository.findById(course.getId()).orElseThrow();
        assertThat(response.reportCount()).isEqualTo(1);
        assertThat(response.reportHidden()).isTrue();
        assertThat(hidden.isReportHidden()).isTrue();
        assertThat(hidden.getReportHiddenReason()).isEqualTo("PRIVATE_PROPERTY_OR_CLOSED_ROAD");
    }

    @Test
    @DisplayName("일반 신고는 세 번째 신고에서 코스를 임시 숨김 처리한다")
    void normalReportsHideCourseAtThirdReport() {
        CourseEntity course = courseRepository.save(publicCourse(owner().getId()));
        UserEntity reporterA = userRepository.save(new UserEntity("reporter-a", "reporter-a@example.test", "hash", "신고자A", null));
        UserEntity reporterB = userRepository.save(new UserEntity("reporter-b", "reporter-b@example.test", "hash", "신고자B", null));
        UserEntity reporterC = userRepository.save(new UserEntity("reporter-c", "reporter-c@example.test", "hash", "신고자C", null));

        CourseReportResponse first = courseReportService.reportCourse(String.valueOf(reporterA.getId()), course.getId(), CourseReportReason.INACCURATE_ROUTE);
        CourseReportResponse second = courseReportService.reportCourse(String.valueOf(reporterB.getId()), course.getId(), CourseReportReason.INACCURATE_ROUTE);
        CourseReportResponse third = courseReportService.reportCourse(String.valueOf(reporterC.getId()), course.getId(), CourseReportReason.INACCURATE_ROUTE);

        assertThat(first.reportHidden()).isFalse();
        assertThat(second.reportHidden()).isFalse();
        assertThat(third.reportCount()).isEqualTo(3);
        assertThat(third.reportHidden()).isTrue();
        assertThat(courseRepository.findById(course.getId()).orElseThrow().isReportHidden()).isTrue();
    }

    @Test
    @DisplayName("같은 사용자는 같은 코스를 중복 신고할 수 없다")
    void duplicateReporterIsRejected() {
        CourseEntity course = courseRepository.save(publicCourse(owner().getId()));
        UserEntity reporter = userRepository.save(new UserEntity("reporter-a", "reporter-a@example.test", "hash", "신고자A", null));
        courseReportService.reportCourse(String.valueOf(reporter.getId()), course.getId(), CourseReportReason.INACCURATE_ROUTE);

        assertThatThrownBy(() -> courseReportService.reportCourse(String.valueOf(reporter.getId()), course.getId(), CourseReportReason.INACCURATE_ROUTE))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("이미 신고한 코스입니다.");
    }

    @Test
    @DisplayName("신고로 숨겨진 코스는 일반 사용자 상세 조회에서 차단되지만 owner는 조회할 수 있다")
    void reportHiddenCourseIsBlockedForNormalUserButReadableForOwner() {
        UserEntity owner = owner();
        CourseEntity course = courseRepository.save(publicCourse(owner.getId()));
        UserEntity reporter = userRepository.save(new UserEntity("reporter-a", "reporter-a@example.test", "hash", "신고자A", null));
        courseReportService.reportCourse(String.valueOf(reporter.getId()), course.getId(), CourseReportReason.PRIVATE_PROPERTY_OR_CLOSED_ROAD);

        assertThatThrownBy(() -> courseReportService.assertReportReadable(course.getId(), null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("신고 누적으로 임시 숨김 처리된 코스입니다.");
        assertThatCode(() -> courseReportService.assertReportReadable(course.getId(), String.valueOf(owner.getId())))
                .doesNotThrowAnyException();
    }

    private UserEntity owner() {
        return userRepository.save(new UserEntity("owner", "owner@example.test", "hash", "코스작성자", null));
    }

    private CourseEntity publicCourse(Long ownerUserId) {
        return new CourseEntity(
                "신고 테스트 코스",
                "설명",
                BigDecimal.valueOf(12.3),
                42,
                1,
                true,
                1,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                ownerUserId,
                CourseVisibility.PUBLIC
        );
    }
}
