package com.bikeprojectminji.bikeback.course.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.course.dto.CourseRoutePointRequest;
import com.bikeprojectminji.bikeback.course.dto.CourseWriteResponse;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class CourseControllerMutationTest extends CourseControllerWebMvcTestSupport {

    @Test
    @DisplayName("기록 기반 코스 생성 API는 인증된 사용자의 코스 생성 결과를 응답한다")
    void createCourseFromRideRecordReturnsWrappedResponse() throws Exception {
        given(courseService.createCourseFromRideRecord("1", new com.bikeprojectminji.bikeback.course.dto.CreateCourseFromRideRecordRequest(1001L, "한강 코스", "설명", "PRIVATE")))
                .willReturn(new CourseWriteResponse(2001L, 1L, "PRIVATE", "한강 코스", 1001L));

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
                .andExpect(jsonPath("$.data.visibility").value("PRIVATE"))
                .andExpect(jsonPath("$.data.sourceRideRecordId").value(1001));
    }

    @Test
    @DisplayName("코스 저장 API는 인증된 사용자의 수정 결과를 응답한다")
    void updateCourseReturnsWrappedResponse() throws Exception {
        given(courseService.updateCourse("1", 2001L, new com.bikeprojectminji.bikeback.course.dto.UpdateCourseRequest(
                "수정 코스",
                "수정 설명",
                "UNLISTED",
                List.of(
                        new CourseRoutePointRequest(1, new BigDecimal("37.4812"), new BigDecimal("126.9527")),
                        new CourseRoutePointRequest(2, new BigDecimal("37.4822"), new BigDecimal("126.9537"))
                )
        )))
                .willReturn(new CourseWriteResponse(2001L, 1L, "UNLISTED", "수정 코스"));

        mockMvc.perform(put("/api/v1/courses/2001")
                        .with(jwt().jwt(jwt -> jwt.subject("1")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "수정 코스",
                                  "description": "수정 설명",
                                  "visibility": "UNLISTED",
                                  "routePoints": [
                                    {
                                      "pointOrder": 1,
                                      "latitude": 37.4812,
                                      "longitude": 126.9527
                                    },
                                    {
                                      "pointOrder": 2,
                                      "latitude": 37.4822,
                                      "longitude": 126.9537
                                    }
                                  ]
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
    @DisplayName("코스 생성 API는 비로그인 요청이면 401을 반환한다")
    void createCourseReturnsUnauthorizedWithoutToken() throws Exception {
        mockMvc.perform(post("/api/v1/courses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }
}
