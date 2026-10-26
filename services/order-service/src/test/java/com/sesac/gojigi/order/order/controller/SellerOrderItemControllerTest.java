package com.sesac.gojigi.order.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.order.dto.response.OrderItemResponse;
import com.sesac.gojigi.order.order.dto.response.SellerBulkResponse;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.service.SellerDeliveryService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class SellerOrderItemControllerTest {

    private static final String BASE = "/api/seller/order-items";

    @Mock
    private SellerDeliveryService sellerDeliveryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerOrderItemController(sellerDeliveryService))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static SellerBulkResponse oneDoneOneFailed() {
        return new SellerBulkResponse(List.of(OrderItemResponse.from(OrderFixtures.item(OrderFixtures.order()))),
                List.of(new SellerBulkResponse.Failure(457L, "ORDER_INVALID_STATUS", "지금 주문 상태에서는 처리할 수 없습니다.")));
    }

    @Test
    @DisplayName("발주확인 · 배송완료 — X-Store-Id 스토어로 처리, 성공 · 실패를 함께 200")
    void confirmAndDeliver() throws Exception {
        given(sellerDeliveryService.prepare(eq(1L), eq(70L), eq(List.of(456L, 457L)))).willReturn(oneDoneOneFailed());
        given(sellerDeliveryService.deliver(eq(1L), eq(70L), eq(List.of(456L)))).willReturn(oneDoneOneFailed());

        mockMvc.perform(patch(BASE + "/confirm").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[456,457]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("발주확인했습니다."))
                .andExpect(jsonPath("$.data.failed[0].code").value("ORDER_INVALID_STATUS"));
        mockMvc.perform(patch(BASE + "/deliver").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[456]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("배송완료 처리했습니다."));
    }

    @Test
    @DisplayName("송장 등록 — 송장번호는 비워도 되고, 형식이 틀리면 400")
    void ship() throws Exception {
        given(sellerDeliveryService.ship(eq(1L), eq(70L), any())).willReturn(oneDoneOneFailed());

        mockMvc.perform(patch(BASE + "/ship").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":456,\"courier\":\"CJ대한통운\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded[0].productName").value("제철 사과 3kg"));
        mockMvc.perform(patch(BASE + "/ship").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"orderItemId\":456,\"courier\":\"CJ대한통운\",\"trackingNo\":\"abc\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

    @Test
    @DisplayName("형식 오류 — 빈 목록 · 택배사 누락은 400, 처리 없음")
    void validation() throws Exception {
        mockMvc.perform(patch(BASE + "/confirm").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderItemIds\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(BASE + "/ship").header("X-User-Id", "70").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"items\":[{\"orderItemId\":456}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(sellerDeliveryService);
    }
}
