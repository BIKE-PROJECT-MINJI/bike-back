package com.bikeprojectminji.bikeback.controller.course;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.course.CourseDetailResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseListItemResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseListResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseRoutePointResponse;
import com.bikeprojectminji.bikeback.dto.course.CourseRoutePointsResponse;
import com.bikeprojectminji.bikeback.global.exception.NotFoundException;
import com.bikeprojectminji.bikeback.service.course.CourseService;
import com.bikeprojectminji.bikeback.service.ridepolicy.RidePolicyService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CourseController.class)
class CourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @MockitoBean
    private RidePolicyService ridePolicyService;

    @Test
    @DisplayName("코스 상세 API는 success 래퍼로 응답한다")
    void getCourseDetailReturnsWrappedResponse() throws Exception {
        CourseDetailResponse response = new CourseDetailResponse(
                7L,
                "아라뱃길 루트",
                BigDecimal.valueOf(23.4),
                95
        );
        given(courseService.getCourseDetail(7L)).willReturn(response);

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
                .given(courseService).getCourseDetail(999L);

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
        given(courseService.getCourseRoutePoints(7L)).willReturn(response);

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
                .given(courseService).getCourseRoutePoints(999L);

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
}
