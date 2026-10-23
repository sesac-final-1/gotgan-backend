package com.sesac.gojigi.order.claim.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
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
class ClaimControllerTest {

    private static final String URL = "/api/orders/20261023-000001/claims";
    private static final String BODY = """
            {"orderItemId":456,"type":"CANCEL","reasonCode":"CHANGE_OF_MIND","reasonDetail":"다시 주문"}""";

    @Mock
    private CancelOrderItemSaga cancelOrderItemSaga;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new ClaimController(cancelOrderItemSaga))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("즉시 취소 — 끝까지 가면 201 '주문을 취소했습니다.', 처리 중이면 201 접수 안내")
    void create() throws Exception {
        given(cancelOrderItemSaga.request(eq(4L), eq("20261023-000001"), any()))
                .willReturn(new ClaimResponse(789L, 456L, ClaimType.CANCEL, ClaimStatus.COMPLETED, 8_000L, 2_000L))
                .willReturn(new ClaimResponse(790L, 456L, ClaimType.CANCEL, ClaimStatus.APPROVED, 8_000L, 2_000L));

        mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("주문을 취소했습니다."))
                .andExpect(jsonPath("$.data.refundAmt").value(8000))
                .andExpect(jsonPath("$.data.restorePoint").value(2000));
        mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("취소를 접수했습니다. 환불을 처리하고 있어요."))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
    }

    @Test
    @DisplayName("형식 오류 — 상품주문 · 유형 · 사유 누락은 400, Saga 호출 없음")
    void create_validation() throws Exception {
        for (String body : new String[] {
                BODY.replace("\"orderItemId\":456,", ""),
                BODY.replace("\"type\":\"CANCEL\",", ""),
                BODY.replace("\"reasonCode\":\"CHANGE_OF_MIND\",", "") }) {
            mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
        verifyNoInteractions(cancelOrderItemSaga);
    }

    @Test
    @DisplayName("결제완료가 아님 — 409 CLAIM_NOT_CANCELLABLE")
    void create_notCancellable() throws Exception {
        given(cancelOrderItemSaga.request(eq(4L), eq("20261023-000001"), any()))
                .willThrow(new ClaimException(ClaimErrorCode.CLAIM_NOT_CANCELLABLE));

        mockMvc.perform(post(URL).header("X-User-Id", "4").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CLAIM_NOT_CANCELLABLE"));
    }
}
