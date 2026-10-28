package com.sesac.gojigi.store.store.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.response.InternalStoreResponse;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.service.StoreQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalStoreControllerTest {

    @Mock
    private StoreQueryService storeQueryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalStoreController(storeQueryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("스토어 상세 조회 — 200, 봉투 data 에 storeId · userId · storeName · status · 배송 정책")
    void get_whenStoreExists_returns200() throws Exception {
        // given
        given(storeQueryService.getInternal(1L)).willReturn(
                new InternalStoreResponse(1L, 2L, "곳간상회", StoreStatus.ACTIVE, 3000L, 30000L, "우체국택배"));

        // when · then
        mockMvc.perform(get("/internal/stores/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.userId").value(2))
                .andExpect(jsonPath("$.data.storeName").value("곳간상회"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.freeShippingThreshold").value(30000))
                .andExpect(jsonPath("$.data.courier").value("우체국택배"));
    }

    @Test
    @DisplayName("무료배송 기준 · 택배사가 없으면 키는 남고 값이 null — 호출자가 null 을 \"무료배송 없음\"으로 읽는다")
    void get_whenNoFreeShippingAndCourier_returnsNullFields() throws Exception {
        // given
        given(storeQueryService.getInternal(3L)).willReturn(
                new InternalStoreResponse(3L, 6L, "새 스토어", StoreStatus.ACTIVE, 3000L, null, null));

        // when · then
        mockMvc.perform(get("/internal/stores/3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.shippingFee").value(3000))
                .andExpect(jsonPath("$.data.freeShippingThreshold").isEmpty())
                .andExpect(jsonPath("$.data.courier").isEmpty());
    }

    @Test
    @DisplayName("없는 스토어 — 404 STORE_NOT_FOUND, data 는 null")
    void get_whenStoreMissing_returns404() throws Exception {
        // given
        given(storeQueryService.getInternal(99L)).willThrow(new StoreException(StoreErrorCode.STORE_NOT_FOUND));

        // when · then
        mockMvc.perform(get("/internal/stores/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_NOT_FOUND"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("storeId 가 숫자가 아니면 400 — 서비스를 부르지 않는다")
    void get_whenStoreIdNotNumber_returns400() throws Exception {
        mockMvc.perform(get("/internal/stores/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
