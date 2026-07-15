package com.bikeprojectminji.bikeback.global.contract;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureMockMvc
class OpenApiRepresentativeContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("OpenAPI는 핵심 주행, 코스, 경로 정책, AI 경로 endpoint 계약을 생성한다")
    void exposesRepresentativePathsAndRequestSchemas() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/v1/ride-records'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ride-records/by-client-ride-id/{clientRideId}'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ride-records'].post.requestBody.content['application/json'].schema['$ref']",
                        containsString("CreateRideRecordRequest")))
                .andExpect(jsonPath("$.paths['/api/v1/courses/{courseId}/route-points'].get").exists())
                .andExpect(jsonPath("$.paths['/api/v1/courses/{courseId}/ride-policy/evaluate'].post").exists())
                .andExpect(jsonPath("$.paths['/api/v1/ai-routes/plan/from-text'].post").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRideRecordRequest.properties.clientRideId").exists())
                .andExpect(jsonPath("$.components.schemas.CreateRideRecordRequest.properties.routePoints").exists())
                .andExpect(jsonPath("$.components.schemas.RidePolicyEvaluationResponse.properties.overallState").exists())
                .andExpect(jsonPath("$.components.schemas.AiRoutePlanResponse.properties.aiWorkerMetadata").exists());
    }
}
