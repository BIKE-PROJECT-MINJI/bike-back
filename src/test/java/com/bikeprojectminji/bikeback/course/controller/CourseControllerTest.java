package com.bikeprojectminji.bikeback.course.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseDetailResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListItemResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseListResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.global.exception.ForbiddenException;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=01234567890123456789012345678901",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @Test
    @DisplayName("코스 상세 API는 success 래퍼로 응답한다")
    void getCourseDetailReturnsWrappedResponse() throws Exception {
        CourseDetailResponse response = new CourseDetailResponse(
                7L,
                "아라뱃길 루트",
                BigDecimal.valueOf(23.4),
                95
        );
        given(courseService.getCourseDetail(7L, null, null)).willReturn(response);

        mockMvc.perform(get("/api/v1/courses/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.title").value("아라뱃길 루트"))
                .andExpect(jsonPath("$.data.distanceKm").value(23.4))
                .andExpect(jsonPath("$.data.estimatedDurationMin").value(95));
    }

    @Test
    @DisplayName("코스 상세 API는 없는 코스면 404를 응답한다")
    void getCourseDetailReturnsNotFoundWhenCourseDoesNotExist() throws Exception {
        willThrow(new NotFoundException("코스를 찾을 수 없습니다."))
                .given(courseService).getCourseDetail(999L, null, null);

        mockMvc.perform(get("/api/v1/courses/999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("코스를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("코스 경로 API는 success 래퍼로 응답한다")
    void getCourseRoutePointsReturnsWrappedResponse() throws Exception {
        CourseRoutePointsResponse response = new CourseRoutePointsResponse(
                7L,
                List.of(
                        new CourseRoutePointResponse(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)),
                        new CourseRoutePointResponse(2, BigDecimal.valueOf(37.5671), BigDecimal.valueOf(126.9792))
                )
        );
        given(courseService.getCourseRoutePoints(7L, null, null)).willReturn(response);

        mockMvc.perform(get("/api/v1/courses/7/route-points"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.courseId").value(7))
                .andExpect(jsonPath("$.data.points[0].pointOrder").value(1))
                .andExpect(jsonPath("$.data.points[1].pointOrder").value(2));
    }

    @Test
    @DisplayName("코스 경로 API는 없는 코스면 404를 응답한다")
    void getCourseRoutePointsReturnsNotFoundWhenCourseDoesNotExist() throws Exception {
        willThrow(new NotFoundException("코스를 찾을 수 없습니다."))
                .given(courseService).getCourseRoutePoints(999L, null, null);

        mockMvc.perform(get("/api/v1/courses/999/route-points"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("코스를 찾을 수 없습니다."));
    }

    @Test
    @DisplayName("전체 코스 목록 API는 success 래퍼로 응답한다")
    void getCoursesReturnsWrappedResponse() throws Exception {
        CourseListResponse response = new CourseListResponse(
                List.of(new CourseListItemResponse(12L, "북한강 초입 코스", BigDecimal.valueOf(42.3), 130)),
                true,
                "12"
        );
        given(courseService.getCourses(null, null)).willReturn(response);

        mockMvc.perform(get("/api/v1/courses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.items[0].id").value(12))
                .andExpect(jsonPath("$.data.items[0].title").value("북한강 초입 코스"))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").value("12"));
    }

    @Test
    @DisplayName("기록 기반 코스 생성 API는 인증된 사용자의 코스 생성 결과를 응답한다")
    void createCourseFromRideRecordReturnsWrappedResponse() throws Exception {
        given(courseService.createCourseFromRideRecord("1", new com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE")))
                .willReturn(new CourseWriteResponse(2001L, 1L, "PRIVATE", "한강 코스"));

        mockMvc.perform(post("/api/v1/courses")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceRideRecordId": 1001,
                                  "name": "한강 코스",
                                  "description": "설명",
                                  "visibility": "PRIVATE"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.courseId").value(2001))
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"));
    }

    @Test
    @DisplayName("코스 저장 API는 인증된 사용자의 수정 결과를 응답한다")
    void updateCourseReturnsWrappedResponse() throws Exception {
        given(courseService.updateCourse("1", 2001L, new com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest("수정 코스", "수정 설명", "UNLISTED", null)))
                .willReturn(new CourseWriteResponse(2001L, 1L, "UNLISTED", "수정 코스"));

        mockMvc.perform(put("/api/v1/courses/2001")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수정 코스",
                                  "description": "수정 설명",
                                  "visibility": "UNLISTED",
                                  "routePoints": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseId").value(2001))
                .andExpect(jsonPath("$.data.visibility").value("UNLISTED"));
    }

    @Test
    @DisplayName("공개 범위 변경 API는 인증된 사용자의 visibility 변경 결과를 응답한다")
    void updateCourseVisibilityReturnsWrappedResponse() throws Exception {
        given(courseService.updateCourseVisibility("1", 2001L, new com.bikeprojectminji.bikeback.course.dto.UpdateCourseVisibilityRequest("PUBLIC")))
                .willReturn(new CourseWriteResponse(2001L, 1L, "PUBLIC", "한강 코스"));

        mockMvc.perform(patch("/api/v1/courses/2001/visibility")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "visibility": "PUBLIC"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.visibility").value("PUBLIC"));
    }

    @Test
    @DisplayName("비공개 코스 상세 API는 비로그인 요청이면 403을 응답한다")
    void getPrivateCourseDetailReturnsForbiddenWithoutToken() throws Exception {
        willThrow(new ForbiddenException("이 코스는 공개되지 않았습니다."))
                .given(courseService).getCourseDetail(2001L, null, null);

        mockMvc.perform(get("/api/v1/courses/2001"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("이 코스는 공개되지 않았습니다."));
    }

    @Test
    @DisplayName("비공개 코스 상세 API는 owner 토큰이면 응답한다")
    void getPrivateCourseDetailReturnsResponseForOwner() throws Exception {
        given(courseService.getCourseDetail(2001L, "1", null)).willReturn(new CourseDetailResponse(2001L, "내 코스", BigDecimal.valueOf(18.3), 60));

        mockMvc.perform(get("/api/v1/courses/2001").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(2001));
    }

    @Test
    @DisplayName("공개 코스 검색 API는 success 래퍼로 응답한다")
    void searchCoursesReturnsWrappedResponse() throws Exception {
        CourseListResponse response = new CourseListResponse(
                List.of(new CourseListItemResponse(2001L, "한강 코스", BigDecimal.valueOf(18.3), 60)),
                false,
                null
        );
        given(courseService.searchPublicCourses("한강", "latest")).willReturn(response);

        mockMvc.perform(get("/api/v1/courses/search").param("q", "한강").param("sort", "latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(2001))
                .andExpect(jsonPath("$.data.items[0].title").value("한강 코스"));
    }

    @Test
    @DisplayName("공유 정보 API는 owner 인증이 있으면 success 래퍼로 응답한다")
    void getCourseShareInfoReturnsWrappedResponse() throws Exception {
        given(courseService.getCourseShareInfo("1", 2001L))
                .willReturn(new CourseShareResponse("UNLISTED_LINK", "UNLISTED", "/api/v1/courses/2001?shareToken=share-token", "share-token"));

        mockMvc.perform(post("/api/v1/courses/2001/share").with(jwt().jwt(jwt -> jwt.subject("1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shareType").value("UNLISTED_LINK"))
                .andExpect(jsonPath("$.data.shareToken").value("share-token"));
    }

    @Test
    @DisplayName("코스 다운로드 API는 share token 기반 응답을 반환한다")
    void downloadCourseReturnsWrappedResponse() throws Exception {
        given(courseService.downloadCourse(2001L, null, "share-token"))
                .willReturn(new CourseDownloadResponse(
                        2001L,
                        "한강 코스",
                        "UNLISTED",
                        List.of(new CourseRoutePointResponse(1, BigDecimal.valueOf(37.5665), BigDecimal.valueOf(126.9780)))
                ));

        mockMvc.perform(get("/api/v1/courses/2001/download").param("shareToken", "share-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courseId").value(2001))
                .andExpect(jsonPath("$.data.visibility").value("UNLISTED"))
                .andExpect(jsonPath("$.data.routePoints[0].pointOrder").value(1));
    }
}
