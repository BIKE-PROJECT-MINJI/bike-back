package com.bikeprojectminji.bikeback.course.controller;

import com.bikeprojectminji.bikeback.course.service.CourseReportService;
import com.bikeprojectminji.bikeback.course.service.CourseQueryService;
import com.bikeprojectminji.bikeback.course.service.CourseService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CourseController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
    "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=3600"
})
abstract class CourseControllerWebMvcTestSupport {

    @Autowired
    protected MockMvc mockMvc;

    @MockitoBean
    protected CourseService courseService;

    @MockitoBean
    protected CourseQueryService courseQueryService;

    @MockitoBean
    protected CourseReportService courseReportService;
}
