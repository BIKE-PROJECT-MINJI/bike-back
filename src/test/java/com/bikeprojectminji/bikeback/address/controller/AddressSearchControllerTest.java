package com.bikeprojectminji.bikeback.address.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bikeprojectminji.bikeback.address.dto.AddressCandidateResponse;
import com.bikeprojectminji.bikeback.address.dto.AddressSearchResponse;
import com.bikeprojectminji.bikeback.address.service.AddressSearchRateLimitService;
import com.bikeprojectminji.bikeback.address.service.AddressSearchService;
import com.bikeprojectminji.bikeback.global.config.SecurityConfig;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AddressSearchController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "auth.jwt.secret=test-only-jwt-secret-32-byte-key!",
        "auth.jwt.issuer=bike-back-test",
        "auth.jwt.token-validity-sec=900"
})
class AddressSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AddressSearchService addressSearchService;

    @MockitoBean
    private AddressSearchRateLimitService addressSearchRateLimitService;

    @Test
    @DisplayName("주소 검색 API는 비로그인 사용자에게도 후보와 상태를 success 래퍼로 반환한다")
    void searchReturnsWrappedAddressCandidates() throws Exception {
        given(addressSearchService.search("북악스카이웨이", 1, 3))
                .willReturn(new AddressSearchResponse(
                        "AMBIGUOUS",
                        1,
                        3,
                        2,
                        "FAKE",
                        "주소 후보가 여러 개입니다.",
                        List.of(
                                new AddressCandidateResponse(
                                        "fake-1",
                                        "북악스카이웨이 팔각정",
                                        "서울 종로구 북악산로 267",
                                        BigDecimal.valueOf(37.6026),
                                        BigDecimal.valueOf(126.9803),
                                        "FAKE",
                                        "PLACE",
                                        "HIGH"
                                )
                        )
                ));

        mockMvc.perform(get("/api/v1/addresses/search")
                        .param("query", "북악스카이웨이")
                        .param("page", "1")
                        .param("size", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("AMBIGUOUS"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(3))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.provider").value("FAKE"))
                .andExpect(jsonPath("$.data.candidates[0].label").value("북악스카이웨이 팔각정"))
                .andExpect(jsonPath("$.data.candidates[0].lat").value(37.6026))
                .andExpect(jsonPath("$.data.candidates[0].lon").value(126.9803))
                .andExpect(jsonPath("$.data.query").doesNotExist());

        verify(addressSearchRateLimitService).checkAllowed("127.0.0.1");
    }
}
