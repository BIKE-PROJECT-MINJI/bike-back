package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
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
class FeaturedCourseServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private BikeMetricsRecorder bikeMetricsRecorder;

    @InjectMocks
    private CourseService courseService;

    @Test
    @DisplayName("위치가 없으면 featuredRank 기준 fallback 추천을 최대 3개 응답한다")
    void getFeaturedCoursesReturnsFallbackCourses() {
        given(courseRepository.findFeaturedCourses()).willReturn(List.of(
                featuredCourse(1L, "아라뱃길 루트", 1, 37.5665000, 126.9780000),
                featuredCourse(2L, "북한강 루트", 2, 37.5700000, 126.9900000),
                featuredCourse(3L, "한강 남단 루트", 3, 37.5400000, 127.0200000),
                featuredCourse(4L, "송도 루트", 4, 37.3900000, 126.6500000)
        ));

        FeaturedCourseResponse response = courseService.getFeaturedCourses(null, null);

        assertThat(response.sortingMode()).isEqualTo("fallback");
        assertThat(response.courses()).hasSize(3);
        assertThat(response.courses()).extracting("featuredRank").containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("위치가 있으면 거리순으로 정렬하고 거리 동률은 featuredRank를 보조 기준으로 사용한다")
    void getFeaturedCoursesReturnsDistanceSortedCourses() {
        given(courseRepository.findFeaturedCourses()).willReturn(List.of(
                featuredCourse(1L, "먼 코스", 2, 37.7000000, 127.2000000),
                featuredCourse(2L, "가까운 코스 A", 2, 37.5001000, 127.0001000),
                featuredCourse(3L, "가까운 코스 B", 1, 37.5001000, 127.0001000),
                featuredCourse(4L, "중간 코스", 4, 37.5400000, 127.0500000)
        ));

        FeaturedCourseResponse response = courseService.getFeaturedCourses(
                BigDecimal.valueOf(37.5000000),
                BigDecimal.valueOf(127.0000000)
        );

        assertThat(response.sortingMode()).isEqualTo("distance");
        assertThat(response.courses()).hasSize(3);
        assertThat(response.courses()).extracting("id").containsExactly(3L, 2L, 4L);
        assertThat(response.courses().get(0).distanceFromUserM()).isNotNull();
    }

    @Test
    @DisplayName("추천 대상이 없으면 fallback empty를 정상 응답한다")
    void getFeaturedCoursesReturnsEmptyResponse() {
        given(courseRepository.findFeaturedCourses()).willReturn(Collections.emptyList());

        FeaturedCourseResponse response = courseService.getFeaturedCourses(null, null);

        assertThat(response.sortingMode()).isEqualTo("fallback");
        assertThat(response.courses()).isEmpty();
    }

    private CourseEntity featuredCourse(Long id, String title, int featuredRank, double lat, double lon) {
        CourseEntity entity = new CourseEntity(
                title,
                BigDecimal.valueOf(23.4),
                95,
                featuredRank,
                true,
                featuredRank,
                BigDecimal.valueOf(lat),
                BigDecimal.valueOf(lon)
        );
        ReflectionTestUtils.setField(entity, "id", id);
        return entity;
    }
}
