package com.sesac.gojigi.store.dashboard.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.dashboard.service.SellerDashboardService;
import com.sesac.gojigi.store.dashboard.service.SellerDashboardService.Dashboard;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class SellerDashboardControllerTest {

    @Mock
    private SellerDashboardService sellerDashboardService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerDashboardController(sellerDashboardService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("대시보드 — 200, X-Store-Id 스토어 통계를 그대로 · 실패 영역은 null + unavailable")
    void dashboard_whenSeller_returns200WithPartial() throws Exception {
        // given
        ObjectNode order = JsonNodeFactory.instance.objectNode().put("toConfirmCount", 2).put("todaySalesAmt", 52000);
        ObjectNode product = JsonNodeFactory.instance.objectNode().put("onSaleCount", 5);
        given(sellerDashboardService.dashboard(1L)).willReturn(new Dashboard(order, null, product, null, List.of("orderDaily", "settlement")));

        // when · then
        mockMvc.perform(get("/api/seller/dashboard").header("X-User-Id", "2").header("X-User-Roles", "SELLER").header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("대시보드를 조회했습니다."))
                .andExpect(jsonPath("$.data.order.toConfirmCount").value(2))
                .andExpect(jsonPath("$.data.order.todaySalesAmt").value(52000))
                .andExpect(jsonPath("$.data.orderDaily").doesNotExist())
                .andExpect(jsonPath("$.data.product.onSaleCount").value(5))
                .andExpect(jsonPath("$.data.unavailable[0]").value("orderDaily"))
                .andExpect(jsonPath("$.data.unavailable[1]").value("settlement"));
    }

    @Test
    @DisplayName("X-Store-Id 가 없으면 403 FORBIDDEN, 사용자 헤더가 없으면 401 — 서비스를 부르지 않는다")
    void dashboard_whenNoStoreOrUser_rejects() throws Exception {
        mockMvc.perform(get("/api/seller/dashboard").header("X-User-Id", "2").header("X-User-Roles", "SELLER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/api/seller/dashboard"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        verifyNoInteractions(sellerDashboardService);
    }
}
