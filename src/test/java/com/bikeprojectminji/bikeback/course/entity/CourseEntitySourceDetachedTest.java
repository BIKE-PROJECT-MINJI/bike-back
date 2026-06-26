package com.bikeprojectminji.bikeback.course.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CourseEntitySourceDetachedTest {

    @Test
    @DisplayName("처음부터 원본 기록이 없는 코스는 분리 상태가 아니다")
    void sourceLessCourseIsNotDetached() {
        CourseEntity course = new CourseEntity(
                "직접 작성 코스",
                "처음부터 자유 주행 원본 없이 작성한 코스",
                BigDecimal.valueOf(12.3),
                40,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                1L,
                CourseVisibility.PRIVATE
        );

        assertThat(course.getSourceRideRecordId()).isNull();
        assertThat(course.isSourceDetached()).isFalse();
    }

    @Test
    @DisplayName("원본 기록이 있던 코스만 삭제 분리 상태가 된다")
    void linkedCourseBecomesDetachedOnlyAfterRideRecordSourceDetach() {
        CourseEntity linkedCourse = new CourseEntity(
                "기록 저장 코스",
                "자유 주행 기록에서 저장한 코스",
                BigDecimal.valueOf(18.2),
                60,
                1,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                1L,
                1001L,
                CourseVisibility.PRIVATE
        );
        CourseEntity sourceLessCourse = new CourseEntity(
                "직접 작성 코스",
                "처음부터 자유 주행 원본 없이 작성한 코스",
                BigDecimal.valueOf(12.3),
                40,
                2,
                false,
                null,
                BigDecimal.valueOf(37.5665),
                BigDecimal.valueOf(126.9780),
                1L,
                CourseVisibility.PRIVATE
        );

        linkedCourse.detachRideRecordSource();
        sourceLessCourse.detachRideRecordSource();

        assertThat(linkedCourse.getSourceRideRecordId()).isNull();
        assertThat(linkedCourse.isSourceDetached()).isTrue();
        assertThat(sourceLessCourse.getSourceRideRecordId()).isNull();
        assertThat(sourceLessCourse.isSourceDetached()).isFalse();
    }
}
