package com.sesac.gojigi.store.store.controller;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.response.PublicStoreResponse;
import com.sesac.gojigi.store.store.dto.response.PublicStoreSummaryResponse;
import com.sesac.gojigi.store.store.entity.StoreGrade;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
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
    @DisplayName("스토어 홈 — 운영 중 200, 판매자 정보 · 배송 정책까지")
    void get_whenActive_returns200() throws Exception {
        // given
        given(storeQueryService.getPublic(1L)).willReturn(new PublicStoreResponse(1L, "산골농부 박씨네", StoreStatus.ACTIVE, "park-farm",
                1L, "<p>소개</p>", null, "#d0701f", StoreGrade.BIG_POWER, OffsetDateTime.parse("2024-03-23T10:00:00+09:00"),
                "박영철", "000-00-00006", "064-0000-0006", 3000L, null, "우체국택배", "수확일 기준 당일 발송"));

        // when · then
        mockMvc.perform(get("/api/stores/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.ownerName").value("박영철"))
                .andExpect(jsonPath("$.data.bizRegNo").value("000-00-00006"))
                .andExpect(jsonPath("$.data.csPhone").value("064-0000-0006"))
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.freeShippingThreshold").value(nullValue()))
                .andExpect(jsonPath("$.data.courier").value("우체국택배"));
    }

    @Test
    @DisplayName("스토어 홈 — 정지 스토어도 200, 이름 · 상태만 있고 판매자 정보는 null")
    void get_whenSuspended_returnsNameAndStatusOnly() throws Exception {
        // given
        given(storeQueryService.getPublic(15L)).willReturn(new PublicStoreResponse(15L, "볕드는집", StoreStatus.SUSPENDED, null, null,
                null, null, null, null, null, null, null, null, null, null, null, null));

        // when · then
        mockMvc.perform(get("/api/stores/15"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeName").value("볕드는집"))
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"))
                .andExpect(jsonPath("$.data.ownerName").value(nullValue()))
                .andExpect(jsonPath("$.data.bizRegNo").value(nullValue()));
    }

    @Test
    @DisplayName("스토어 홈 — 없으면 404 STORE_NOT_FOUND, 숫자가 아니면 400 INVALID_INPUT")
    void get_whenMissingOrInvalidId_returnsError() throws Exception {
        // given
        given(storeQueryService.getPublic(99L)).willThrow(new StoreException(StoreErrorCode.STORE_NOT_FOUND));

        // when · then
        mockMvc.perform(get("/api/stores/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"));
        mockMvc.perform(get("/api/stores/park-farm"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
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
