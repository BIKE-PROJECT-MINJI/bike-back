package com.bikeprojectminji.bikeback.course.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.service.AuthService;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.course.dto.FeaturedCourseResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseRoutePointEntity;
import com.bikeprojectminji.bikeback.course.entity.CourseVisibility;
import com.bikeprojectminji.bikeback.course.repository.CourseListRow;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.course.repository.FeaturedCourseDistanceCandidate;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.global.metrics.BikeMetricsRecorder;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class CourseQueryServiceTest {

    @Mock
    private CourseRepository courseRepository;

    @Mock
    private AuthService authService;

    @Mock
    private BikeMetricsRecorder bikeMetricsRecorder;

    @Mock
    private CourseRouteSnapshotService courseRouteSnapshotService;

    @InjectMocks
    private CourseQueryService courseQueryService;

    @Test
    @DisplayName("코스 상세 조회는 단건 상세를 응답한다")
    void getCourseDetailReturnsSingleCourse() {
        CourseEntity entity = course(7L, "아라뱃길 루트", CourseVisibility.PUBLIC);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        CourseDetailResponse response = courseQueryService.getCourseDetail(7L, null, null);

        assertThat(response.id()).isEqualTo(7L);
        assertThat(response.title()).isEqualTo("아라뱃길 루트");
        assertThat(response.distanceKm()).isEqualByComparingTo("23.4");
        assertThat(response.estimatedDurationMin()).isEqualTo(95);
    }

    @Test
    @DisplayName("코스 상세 조회는 없는 코스면 NotFoundException을 던진다")
    void getCourseDetailThrowsWhenCourseDoesNotExist() {
        given(courseRepository.findById(999L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> courseQueryService.getCourseDetail(999L, null, null))
                .isInstanceOf(NotFoundException.class)
                .hasMessage("코스를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("코스 경로 조회는 pointOrder 오름차순 좌표 목록을 응답한다")
    void getCourseRoutePointsReturnsOrderedPoints() {
        CourseEntity entity = course(7L, "공개 코스", CourseVisibility.PUBLIC);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(courseRouteSnapshotService.get(7L, "route_points")).willReturn(snapshot(7L, List.of(
                new CourseRoutePointEntity(7L, 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                new CourseRoutePointEntity(7L, 2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
        )));

        CourseRoutePointsResponse response = courseQueryService.getCourseRoutePoints(7L, null, null);

        assertThat(response.courseId()).isEqualTo(7L);
        assertThat(response.points()).hasSize(2);
        assertThat(response.points().get(0).pointOrder()).isEqualTo(1);
        assertThat(response.points().get(1).pointOrder()).isEqualTo(2);
    }

    @Test
    @DisplayName("코스가 없으면 빈 목록과 종료 상태를 응답한다")
    void getCoursesReturnsEmptyPage() {
        given(courseRepository.findPublicListPageAfter(null, 11)).willReturn(Collections.emptyList());

        CourseListResponse response = courseQueryService.getCourses(null, null);

        assertThat(response.items()).isEmpty();
        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    @DisplayName("limit보다 하나 더 조회되면 hasNext와 nextCursor를 계산한다")
    void getCoursesReturnsNextCursorWhenMorePagesExist() {
        given(courseRepository.findPublicListPageAfter(null, 11)).willReturn(courseListRows(11));

        CourseListResponse response = courseQueryService.getCourses(null, 10);

        assertThat(response.items()).hasSize(10);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("10");
    }

    @Test
    @DisplayName("공개 코스 목록은 limit 상한을 50개로 제한한다")
    void getCoursesCapsLimitToFifty() {
        given(courseRepository.findPublicListPageAfter(null, 51)).willReturn(courseListRows(51));

        CourseListResponse response = courseQueryService.getCourses(null, 1000);

        assertThat(response.items()).hasSize(50);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo("50");
    }

    @Test
    @DisplayName("비공개 코스 상세 조회는 owner가 아니면 ForbiddenException을 던진다")
    void getCourseDetailThrowsWhenPrivateCourseIsNotOwned() {
        CourseEntity entity = course(7L, "비공개 코스", CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(entity, "ownerUserId", 9L);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> courseQueryService.getCourseDetail(7L, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스는 공개되지 않았습니다.");
    }

    @Test
    @DisplayName("비공개 코스 상세 조회는 owner면 응답한다")
    void getCourseDetailReturnsPrivateCourseForOwner() {
        CourseEntity entity = course(7L, "비공개 코스", CourseVisibility.PRIVATE);
        ReflectionTestUtils.setField(entity, "ownerUserId", 1L);
        UserEntity user = user(1L);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(authService.findUserBySubject("1")).willReturn(user);

        CourseDetailResponse response = courseQueryService.getCourseDetail(7L, "1", null);

        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("UNLISTED 코스 상세 조회는 owner면 share token 없이 응답한다")
    void getCourseDetailReturnsUnlistedCourseForOwnerWithoutShareToken() {
        CourseEntity entity = course(7L, "링크 공유 코스", CourseVisibility.UNLISTED);
        ReflectionTestUtils.setField(entity, "ownerUserId", 1L);
        ReflectionTestUtils.setField(entity, "shareToken", "share-token");
        UserEntity user = user(1L);
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(authService.findUserBySubject("1")).willReturn(user);

        CourseDetailResponse response = courseQueryService.getCourseDetail(7L, "1", null);

        assertThat(response.id()).isEqualTo(7L);
    }

    @Test
    @DisplayName("공개 코스 검색은 PUBLIC 코스만 latest 기준으로 응답한다")
    void searchPublicCoursesReturnsPublicOnlyItems() {
        given(courseRepository.findTop20ByVisibilityAndReportHiddenFalseAndTitleContainingIgnoreCaseOrderByIdDesc(CourseVisibility.PUBLIC, "한강"))
                .willReturn(List.of(course(1L, "한강 1", CourseVisibility.PUBLIC), course(2L, "한강 2", CourseVisibility.PUBLIC)));

        CourseListResponse response = courseQueryService.searchPublicCourses("한강", "latest");

        assertThat(response.items()).hasSize(2);
        assertThat(response.hasNext()).isFalse();
    }

    @Test
    @DisplayName("UNLISTED 코스 다운로드는 share token이 맞으면 허용한다")
    void downloadCourseReturnsPayloadForUnlistedWithShareToken() {
        CourseEntity entity = course(7L, "공유 코스", CourseVisibility.UNLISTED);
        ReflectionTestUtils.setField(entity, "shareToken", "share-token");
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));
        given(courseRouteSnapshotService.get(7L, "course_download")).willReturn(snapshot(7L, List.of(
                new CourseRoutePointEntity(7L, 1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780))
        )));

        CourseDownloadResponse response = courseQueryService.downloadCourse(7L, null, "share-token");

        assertThat(response.courseId()).isEqualTo(7L);
        assertThat(response.visibility()).isEqualTo("UNLISTED");
        assertThat(response.routePoints()).hasSize(1);
    }

    @Test
    @DisplayName("UNLISTED 코스 다운로드는 share token이 없으면 ForbiddenException을 던진다")
    void downloadCourseThrowsWhenUnlistedShareTokenMissing() {
        CourseEntity entity = course(7L, "공유 코스", CourseVisibility.UNLISTED);
        ReflectionTestUtils.setField(entity, "shareToken", "share-token");
        given(courseRepository.findById(7L)).willReturn(Optional.of(entity));

        assertThatThrownBy(() -> courseQueryService.downloadCourse(7L, null, null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("이 코스에 접근할 권한이 없습니다.");
    }

    @Test
    @DisplayName("위치가 없으면 featuredRank 기준 fallback 추천을 최대 3개 응답한다")
    void getFeaturedCoursesReturnsFallbackCourses() {
        given(courseRepository.findFeaturedCourses()).willReturn(List.of(
                featuredCourse(1L, "아라뱃길 루트", 1, 37.5665000, 126.9780000),
                featuredCourse(2L, "북한강 루트", 2, 37.5700000, 126.9900000),
                featuredCourse(3L, "한강 남단 루트", 3, 37.5400000, 127.0200000),
                featuredCourse(4L, "송도 루트", 4, 37.3900000, 126.6500000)
        ));

        FeaturedCourseResponse response = courseQueryService.getFeaturedCourses(null, null);

        assertThat(response.sortingMode()).isEqualTo("fallback");
        assertThat(response.courses()).hasSize(3);
        assertThat(response.courses()).extracting("featuredRank").containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("위치가 있으면 PostGIS 거리 후보를 우선 사용한다")
    void getFeaturedCoursesUsesPostgisDistanceCandidatesFirst() {
        BigDecimal lat = BigDecimal.valueOf(37.5000000);
        BigDecimal lon = BigDecimal.valueOf(127.0000000);
        CourseEntity nearest = featuredCourse(2L, "PostGIS 가까운 코스", 2, 37.5001000, 127.0001000);
        CourseEntity second = featuredCourse(4L, "PostGIS 중간 코스", 4, 37.5400000, 127.0500000);
        given(courseRepository.findFeaturedCoursesNear(lat, lon, 3)).willReturn(List.of(
                new FeaturedCourseDistanceCandidate(nearest, 16),
                new FeaturedCourseDistanceCandidate(second, 6120)
        ));

        FeaturedCourseResponse response = courseQueryService.getFeaturedCourses(lat, lon);

        assertThat(response.sortingMode()).isEqualTo("distance");
        assertThat(response.courses()).extracting("id").containsExactly(2L, 4L);
        assertThat(response.courses()).extracting("distanceFromUserM").containsExactly(16, 6120);
        verify(courseRepository, never()).findFeaturedCourses();
    }

    private CourseEntity course(Long id, String title, CourseVisibility visibility) {
        CourseEntity entity = new CourseEntity(title, BigDecimal.valueOf(23.4), 95, 1);
        ReflectionTestUtils.setField(entity, "id", id);
        ReflectionTestUtils.setField(entity, "visibility", visibility);
        return entity;
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

    private UserEntity user(Long id) {
        UserEntity user = new UserEntity(null, "bikeoasis@example.com", "encoded-password", "bikeoasis", null);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    private List<CourseListRow> courseListRows(int count) {
        return java.util.stream.IntStream.rangeClosed(1, count)
                .mapToObj(index -> new CourseListRow(
                        (long) index,
                        "코스 " + index,
                        BigDecimal.valueOf(index),
                        60 + index
                ))
                .toList();
    }

    private CourseRouteSnapshot snapshot(Long courseId, List<CourseRoutePointEntity> routePoints) {
        return new CourseRouteSnapshot(
                courseId,
                routePoints,
                routePoints.stream()
                        .map(routePoint -> new CourseRoutePointResponse(routePoint.getPointOrder(), routePoint.getLatitude(), routePoint.getLongitude()))
                        .toList(),
                new com.bikeprojectminji.bikeback.ride.policy.service.RouteProjectionIndex(routePoints)
        );
    }
}
