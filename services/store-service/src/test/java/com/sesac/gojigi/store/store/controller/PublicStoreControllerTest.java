package com.sesac.gojigi.store.store.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.response.PublicStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PublicStoreControllerTest {

    @Mock
    private StoreQueryService storeQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PublicStoreController(storeQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("스토어 목록 — 로그인 헤더 없이 200, 숫자 storeId · 카테고리 ID · 이미지 없음 null · 등급 · +09:00")
    void list_returns200WithSummaries() throws Exception {
        // given
        given(storeQueryService.getPublicList()).willReturn(List.of(new PublicStoreSummaryResponse(1L, "산골농부 박씨네", "park-farm", 1L,
                "<p>소개</p>", null, "#d0701f", StoreGrade.BIG_POWER, OffsetDateTime.parse("2024-03-23T10:00:00+09:00"))));

        // when · then
        mockMvc.perform(get("/api/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].storeId").value(1))
                .andExpect(jsonPath("$.data[0].storeName").value("산골농부 박씨네"))
                .andExpect(jsonPath("$.data[0].slug").value("park-farm"))
                .andExpect(jsonPath("$.data[0].categoryId").value(1))
                .andExpect(jsonPath("$.data[0].intro").value("<p>소개</p>"))
                .andExpect(jsonPath("$.data[0].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.data[0].color").value("#d0701f"))
                .andExpect(jsonPath("$.data[0].grade").value("BIG_POWER"))
                .andExpect(jsonPath("$.data[0].createdAt").value("2024-03-23T10:00:00+09:00"));
    }

    @Test
    @DisplayName("운영 중 스토어가 없으면 빈 배열 200")
    void list_whenNone_returnsEmptyArray() throws Exception {
        // given
        given(storeQueryService.getPublicList()).willReturn(List.of());

        // when · then
        mockMvc.perform(get("/api/stores"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }
}
