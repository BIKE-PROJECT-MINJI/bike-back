package com.bikeprojectminji.bikeback.course.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.course.dto.CourseDownloadResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseReportResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.course.dto.CourseShareResponse;
import com.bikeprojectminji.bikeback.course.entity.CourseReportReason;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CourseControllerReportShareTest extends CourseControllerWebMvcTestSupport {

    @Test
    @DisplayName("코스 신고 API는 인증된 사용자의 신고 결과를 success 래퍼로 응답한다")
    void reportCourseReturnsWrappedResponse() throws Exception {
        given(courseReportService.reportCourse("2", 2001L, CourseReportReason.PRIVATE_PROPERTY_OR_CLOSED_ROAD))
                .willReturn(new CourseReportResponse(2001L, 1, true, "PRIVATE_PROPERTY_OR_CLOSED_ROAD"));

        mockMvc.perform(post("/api/v1/courses/2001/reports")
                        .with(jwt().jwt(jwt -> jwt.subject("2")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "PRIVATE_PROPERTY_OR_CLOSED_ROAD"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.courseId").value(2001))
                .andExpect(jsonPath("$.data.reportCount").value(1))
                .andExpect(jsonPath("$.data.reportHidden").value(true));
    }

    @Test
    @DisplayName("코스 신고 API는 신고 사유가 없으면 400을 반환한다")
    void reportCourseReturnsBadRequestWithoutReason() throws Exception {
        mockMvc.perform(post("/api/v1/courses/2001/reports")
                        .with(jwt().jwt(jwt -> jwt.subject("2")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("reason은 비어 있을 수 없습니다."));
    }

    @Test
    @DisplayName("코스 신고 API는 비로그인 요청이면 401을 반환한다")
    void reportCourseReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/courses/2001/reports")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "PRIVATE_PROPERTY_OR_CLOSED_ROAD"
                                }
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
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
    @DisplayName("공유 정보 API는 비로그인 요청이면 401을 반환한다")
    void getCourseShareInfoReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/courses/2001/share"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    @DisplayName("코스 다운로드 API는 share token 기반 응답을 반환한다")
    void downloadCourseReturnsWrappedResponse() throws Exception {
        given(courseQueryService.downloadCourse(2001L, null, "share-token"))
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
