package com.bikeprojectminji.bikeback.controller.course;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseItemResponse;
import com.bikeprojectminji.bikeback.dto.course.FeaturedCourseResponse;
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
class FeaturedCourseControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CourseService courseService;

    @MockitoBean
    private RidePolicyService ridePolicyService;

    @Test
    @DisplayName("추천 코스 API는 sortingMode와 코스 목록을 success 래퍼로 응답한다")
    void getFeaturedCoursesReturnsWrappedResponse() throws Exception {
        FeaturedCourseResponse response = new FeaturedCourseResponse(
                "distance",
                List.of(new FeaturedCourseItemResponse(1L, "아라뱃길 루트", BigDecimal.valueOf(23.4), 95, 850, 1))
        );
        given(courseService.getFeaturedCourses(BigDecimal.valueOf(37.5), BigDecimal.valueOf(127.0))).willReturn(response);

        mockMvc.perform(get("/api/v1/courses/featured")
                        .param("lat", "37.5")
                        .param("lon", "127.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.sortingMode").value("distance"))
                .andExpect(jsonPath("$.data.courses[0].id").value(1))
                .andExpect(jsonPath("$.data.courses[0].distanceFromUserM").value(850))
                .andExpect(jsonPath("$.data.courses[0].featuredRank").value(1));
    }

    @Test
    @DisplayName("lat만 전달되면 400을 응답한다")
    void getFeaturedCoursesReturnsBadRequestWhenLocationPairIsBroken() throws Exception {
        mockMvc.perform(get("/api/v1/courses/featured")
                        .param("lat", "37.5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("lat와 lon은 함께 전달되어야 합니다."));
    }

    @Test
    @DisplayName("lat 범위를 벗어나면 400을 응답한다")
    void getFeaturedCoursesReturnsBadRequestWhenLatOutOfRange() throws Exception {
        mockMvc.perform(get("/api/v1/courses/featured")
                        .param("lat", "91")
                        .param("lon", "127.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("lat는 -90 이상 90 이하여야 합니다."));
    }
}
