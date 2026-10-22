package com.sesac.gojigi.order.order.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.order.dto.response.OrderCreateResponse;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.saga.service.PlaceOrderSaga;
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
class OrderControllerTest {

    private static final String BODY = """
            {"items":[{"productOptionId":11,"quantity":2}],"receiverName":"김구매","receiverPhone":"010-1234-5678",
             "zipcode":"06236","address1":"서울 강남구 테헤란로 1","address2":"101호","deliveryMemo":"문 앞","usePoint":2000}""";

    @Mock
    private PlaceOrderSaga placeOrderSaga;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new OrderController(placeOrderSaga))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("주문 생성 — 201, 결제창에 넣을 portonePaymentId · payAmt · orderName")
    void create() throws Exception {
        given(placeOrderSaga.place(eq(4L), any())).willReturn(new OrderCreateResponse("20261022-000123",
                OrderStatus.PENDING, 62_000L, "pay-20261022-000123", "제철 사과 3kg"));

        mockMvc.perform(post("/api/orders").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("주문을 생성했습니다. 결제를 진행해 주세요."))
                .andExpect(jsonPath("$.data.portonePaymentId").value("pay-20261022-000123"))
                .andExpect(jsonPath("$.data.payAmt").value(62000));
    }

    @Test
    @DisplayName("전액 포인트 — 201 PAID, 결제 진행 안내 없음")
    void create_zeroPayAmount() throws Exception {
        given(placeOrderSaga.place(eq(4L), any())).willReturn(new OrderCreateResponse("20261022-000123",
                OrderStatus.PAID, 0L, null, "제철 사과 3kg"));

        mockMvc.perform(post("/api/orders").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("주문을 완료했습니다."))
                .andExpect(jsonPath("$.data.status").value("PAID"));
    }

    @Test
    @DisplayName("형식 오류 — 상품 없음 · 수량 0 · 휴대폰 아님 · 음수 포인트는 400, Saga 호출 없음")
    void create_validation() throws Exception {
        for (String body : new String[] {
                BODY.replace("[{\"productOptionId\":11,\"quantity\":2}]", "[]"),
                BODY.replace("\"quantity\":2", "\"quantity\":0"),
                BODY.replace("010-1234-5678", "02-123-4567"),
                BODY.replace("\"usePoint\":2000", "\"usePoint\":-1") }) {
            mockMvc.perform(post("/api/orders").header("X-User-Id", "4")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(placeOrderSaga);
    }

    @Test
    @DisplayName("재고 부족 — 409 ORDER_OUT_OF_STOCK")
    void create_outOfStock() throws Exception {
        given(placeOrderSaga.place(eq(4L), any())).willThrow(new OrderException(OrderErrorCode.ORDER_OUT_OF_STOCK));

        mockMvc.perform(post("/api/orders").header("X-User-Id", "4")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_OUT_OF_STOCK"));
    }
}
