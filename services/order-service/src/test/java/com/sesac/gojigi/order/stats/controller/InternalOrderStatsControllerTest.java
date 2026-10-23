package com.sesac.gojigi.order.stats.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.stats.dto.response.PlatformOrderStatsResponse;
import com.sesac.gojigi.order.stats.dto.response.StoreOrderStatsResponse;
import com.sesac.gojigi.order.stats.service.OrderStatsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class InternalOrderStatsControllerTest {

    @Mock
    private OrderStatsService orderStatsService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new InternalOrderStatsController(orderStatsService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("storeId 가 있으면 스토어 통계 — 미처리 합계 inProgressCount 포함")
    void storeStats() throws Exception {
        given(orderStatsService.getStoreStats(1L)).willReturn(StoreOrderStatsResponse.of(1L, 2L, 1L, 3L, 40_000L));

        mockMvc.perform(get("/internal/stats/orders").param("storeId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toConfirmCount").value(2))
                .andExpect(jsonPath("$.data.inProgressCount").value(6))
                .andExpect(jsonPath("$.data.todaySalesAmt").value(40000));
        verify(orderStatsService, never()).getPlatformStats();
    }

    @Test
    @DisplayName("storeId 가 없으면 전체 통계 — 거래액 · 주문 수")
    void platformStats() throws Exception {
        given(orderStatsService.getPlatformStats()).willReturn(new PlatformOrderStatsResponse(1_250_000L, 37L));

        mockMvc.perform(get("/internal/stats/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("전체 주문 통계를 조회했습니다."))
                .andExpect(jsonPath("$.data.totalTradeAmt").value(1250000))
                .andExpect(jsonPath("$.data.orderCount").value(37));
    }

    @Test
    @DisplayName("storeId 가 숫자가 아니면 400 INVALID_INPUT")
    void storeStats_invalidStoreId() throws Exception {
        mockMvc.perform(get("/internal/stats/orders").param("storeId", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }
}
