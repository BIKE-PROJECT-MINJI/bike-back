package com.bikeprojectminji.bikeback.service.course;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.entity.course.CourseEntity;
import com.bikeprojectminji.bikeback.repository.course.CourseRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CourseServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @InjectMocks
    private CourseService courseService;

    @Test
    @DisplayName("코스가 없으면 빈 목록과 종료 상태를 응답한다")
    void getCoursesReturnsEmptyPage() {
        given(courseRepository.findPageAfter(null, 11)).willReturn(Collections.emptyList());

        CourseListResponse response = courseService.getCourses(null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("limit보다 하나 더 조회되면 hasNext와 nextCursor를 계산한다")
    void getCoursesReturnsNextCursorWhenMorePagesExist() {
        List<CourseEntity> courses = createCourses(11);
        given(courseRepository.findPageAfter(null, 11)).willReturn(courses);

        CourseListResponse response = courseService.getCourses(null, 10);

        assertThat(response.items()).hasSize(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("10");
    }

    private List<CourseEntity> createCourses(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> {
                    CourseEntity entity = new CourseEntity(
                            "코스 " + index,
                            BigDecimal.valueOf(index),
                            60 + index,
                            index
                    );
                    ReflectionTestUtils.setField(entity, "id", (long) index);
                    return entity;
                })
                .toList();
    }
}
