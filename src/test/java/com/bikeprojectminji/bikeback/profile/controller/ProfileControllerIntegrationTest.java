package com.bikeprojectminji.bikeback.profile.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.auth.entity.UserEntity;
import com.bikeprojectminji.bikeback.auth.repository.UserRepository;
import com.bikeprojectminji.bikeback.course.repository.CourseRepository;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import com.bikeprojectminji.bikeback.ride.repository.RideRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class ProfileControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private RideRecordRepository rideRecordRepository;

    @Autowired
    private CourseRepository courseRepository;

    private Long savedUserId;

    @BeforeEach
    void setUp() {
        rideRecordRepository.deleteAll();
        courseRepository.deleteAll();
        userRepository.deleteAll();

        UserEntity user = new UserEntity("external-profile-1", "profile@example.com", null, "profile-user", null);
        savedUserId = userRepository.save(user).getId();
    }

    @Test
    @DisplayName("내 활동 요약 API는 데이터가 없어도 200과 빈 요약을 반환한다")
    void getMyActivitySummaryReturnsEmptySummaryWhenNoDataExists() throws Exception {
        mockMvc.perform(get("/api/v1/profile/me/activity-summary")
                        .with(jwt().jwt(jwt -> jwt.subject(String.valueOf(savedUserId)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.weeklySummary.distanceKm").exists())
                .andExpect(jsonPath("$.data.weeklySummary.rideCount").exists())
                .andExpect(jsonPath("$.data.overallSummary.totalDistanceKm").exists())
                .andExpect(jsonPath("$.data.overallSummary.avgSpeedKmh").exists());
    }
}
