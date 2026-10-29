package com.sesac.gojigi.store.store.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.store.store.dto.response.StoreCloseResponse;
import com.sesac.gojigi.store.store.entity.StoreStatus;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import com.sesac.gojigi.store.store.service.StoreStatusService;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerStoreControllerTest {

    @Mock
    private StoreStatusService storeStatusService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerStoreController(storeStatusService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("폐점 — 200, X-Store-Id 스토어를 폐점하고 상태 CLOSED · 폐점 시각 +09:00")
    void close_whenSeller_returns200() throws Exception {
        // given
        given(storeStatusService.closeBySeller(1L))
                .willReturn(new StoreCloseResponse(1L, StoreStatus.CLOSED, OffsetDateTime.parse("2026-10-29T15:00:00+09:00")));

        // when · then
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("스토어를 폐점했습니다."))
                .andExpect(jsonPath("$.data.storeId").value(1))
                .andExpect(jsonPath("$.data.status").value("CLOSED"))
                .andExpect(jsonPath("$.data.closedAt").value("2026-10-29T15:00:00+09:00"));
    }

    @Test
    @DisplayName("X-Store-Id 가 없으면 403 FORBIDDEN, 사용자 헤더가 없으면 401 — 서비스를 부르지 않는다")
    void close_whenNoStoreOrUser_rejects() throws Exception {
        mockMvc.perform(seller(patch("/api/seller/store/close")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(patch("/api/seller/store/close"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        then(storeStatusService).should(never()).closeBySeller(any());
    }

    @Test
    @DisplayName("진행 중 주문 409 · 미정산 409 는 코드와 서비스가 만든 안내 문구 그대로, order · settlement 장애는 502")
    void close_whenRejected_returnsCodeAndMessage() throws Exception {
        // given
        given(storeStatusService.closeBySeller(1L)).willThrow(
                new StoreException(StoreErrorCode.STORE_ORDERS_IN_PROGRESS, "진행 중인 주문 3건이 모두 끝난 뒤 폐점할 수 있습니다."));
        given(storeStatusService.closeBySeller(2L)).willThrow(
                new StoreException(StoreErrorCode.STORE_SETTLEMENT_OUTSTANDING, "미정산 90,000원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다."));
        given(storeStatusService.closeBySeller(3L)).willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR));

        // when · then
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_ORDERS_IN_PROGRESS"))
                .andExpect(jsonPath("$.message").value("진행 중인 주문 3건이 모두 끝난 뒤 폐점할 수 있습니다."));
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STORE_SETTLEMENT_OUTSTANDING"))
                .andExpect(jsonPath("$.message").value("미정산 90,000원 — 지급 완료(매주 목요일) 후 폐점할 수 있습니다."));
        mockMvc.perform(seller(patch("/api/seller/store/close")).header("X-Store-Id", "3"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
    }

    private static MockHttpServletRequestBuilder seller(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "2").header("X-User-Roles", "SELLER");
    }
}
