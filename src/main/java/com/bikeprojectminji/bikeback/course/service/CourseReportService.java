package com.bikeprojectminji.bikeback.course.service;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseReportResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseReportEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseReportReason;
import com.bikeprojectminji.bikeback.course.repository.CourseReportRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.exception.BadRequestException;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import java.time.Clock;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CourseReportService {

    private static final int NORMAL_REPORT_HIDE_THRESHOLD = 3;

    private final CourseRepository courseRepository;
    private final CourseReportRepository courseReportRepository;
    private final AuthService authService;
    private final Clock clock;

    public CourseReportService(
            CourseRepository courseRepository,
            CourseReportRepository courseReportRepository,
            AuthService authService,
            Clock clock
    ) {
        this.courseRepository = courseRepository;
        this.courseReportRepository = courseReportRepository;
        this.authService = authService;
        this.clock = clock;
    }

    @Transactional
    public CourseReportResponse reportCourse(String subject, Long courseId, CourseReportReason reason) {
        if (reason == null) {
            throw new BadRequestException("reason은 비어 있을 수 없습니다.");
        }
        UserEntity reporter = authService.findUserBySubject(subject);
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        if (course.getOwnerUserId() != null && course.getOwnerUserId().equals(reporter.getId())) {
            throw new BadRequestException("본인 코스는 신고할 수 없습니다.");
        }
        if (courseReportRepository.existsByCourseIdAndReporterUserId(course.getId(), reporter.getId())) {
            throw new BadRequestException("이미 신고한 코스입니다.");
        }

        courseReportRepository.save(CourseReportEntity.create(course.getId(), reporter.getId(), reason, clock));
        long reportCount = courseReportRepository.countByCourseId(course.getId());
        if (shouldHide(reason, reportCount)) {
            course.hideByReport(reason.name(), clock);
        }
        CourseEntity savedCourse = courseRepository.save(course);
        return new CourseReportResponse(
                savedCourse.getId(),
                reportCount,
                savedCourse.isReportHidden(),
                savedCourse.getReportHiddenReason()
        );
    }

    public void assertReportReadable(Long courseId, String subject) {
        CourseEntity course = courseRepository.findById(courseId)
                .orElseThrow(() -> new NotFoundException("코스를 찾을 수 없습니다."));
        if (!course.isReportHidden()) {
            return;
        }
        UserEntity user = subject == null || subject.isBlank() ? null : authService.findUserBySubject(subject);
        if (user != null && course.getOwnerUserId() != null && course.getOwnerUserId().equals(user.getId())) {
            return;
        }
        throw new ForbiddenException("신고 누적으로 임시 숨김 처리된 코스입니다.");
    }

    private boolean shouldHide(CourseReportReason reason, long reportCount) {
        return reason.isHighRisk() || reportCount >= NORMAL_REPORT_HIDE_THRESHOLD;
    }
}
