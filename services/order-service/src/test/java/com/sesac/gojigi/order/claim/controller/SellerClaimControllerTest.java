package com.sesac.gojigi.order.claim.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.order.claim.dto.request.ClaimStage;
import com.sesac.gojigi.order.claim.dto.response.ClaimDetailResponse;
import com.sesac.gojigi.order.claim.dto.response.SellerClaimListResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.service.ClaimQueryService;
import com.sesac.gojigi.order.claim.service.SellerClaimTxService;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.saga.service.CancelOrderItemSaga;
import java.util.List;
import java.util.Map;
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
class SellerClaimControllerTest {

    private static final String BASE = "/api/seller/claims";

    @Mock private ClaimQueryService claimQueryService;
    @Mock private SellerClaimTxService sellerClaimTx;
    @Mock private CancelOrderItemSaga cancelOrderItemSaga;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new SellerClaimController(claimQueryService, sellerClaimTx, cancelOrderItemSaga))
                .setCustomArgumentResolvers(new CurrentUserArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static ClaimDetailResponse claim(ClaimType type, ClaimStatus status) {
        return new ClaimDetailResponse(789L, type, status, ClaimReasonCode.DEFECTIVE, "상세", null, 18_000L, 2_000L,
                null, null, 456L, "20261027-000001-01", "20261027-000001", "상품", "특", null, 1, 20_000L,
                OrderItemStatus.RETURN_REQUESTED, "곳간지기", null, null);
    }

    private org.springframework.test.web.servlet.ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req)
            throws Exception {
        return mockMvc.perform(req.header("X-User-Id", "70").header("X-Store-Id", "1"));
    }

    @Test
    @DisplayName("목록 · 상세 — 기본 단계 OPEN · 페이징 기본값, 단계 값이 잘못되면 400")
    void listAndGet() throws Exception {
        given(claimQueryService.listSeller(1L, ClaimStage.OPEN, null, 0, 20)).willReturn(new SellerClaimListResponse(
                Map.of(ClaimStage.OPEN, 1L), Map.of(ClaimType.RETURN, 1L),
                new PageResponse<>(List.of(claim(ClaimType.RETURN, ClaimStatus.REQUESTED)), 0, 20, 1, 1, false)));
        given(claimQueryService.getSeller(1L, 789L)).willReturn(claim(ClaimType.RETURN, ClaimStatus.REQUESTED));

        call(get(BASE)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stageCounts.OPEN").value(1))
                .andExpect(jsonPath("$.data.items.content[0].claimId").value(789));
        call(get(BASE + "/789")).andExpect(status().isOk()).andExpect(jsonPath("$.data.buyerName").value("곳간지기"));
        call(get(BASE).param("stage", "NOPE")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("승인 · 거부(사유 필수) · 재배송 송장 — 스토어 · 판매자로 처리")
    void approveRejectReship() throws Exception {
        given(sellerClaimTx.approve(1L, 789L)).willReturn(claim(ClaimType.RETURN, ClaimStatus.APPROVED));
        given(sellerClaimTx.reject(1L, 70L, 789L, "사용 흔적")).willReturn(claim(ClaimType.RETURN, ClaimStatus.REJECTED));
        given(sellerClaimTx.reship(1L, 70L, 789L, "CJ대한통운", null)).willReturn(claim(ClaimType.EXCHANGE, ClaimStatus.RESHIPPED));

        call(patch(BASE + "/789/approve")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("요청을 승인했습니다. 상품을 회수해 주세요."))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        call(patch(BASE + "/789/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"사용 흔적\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("REJECTED"));
        call(patch(BASE + "/789/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        call(patch(BASE + "/789/reship").contentType(MediaType.APPLICATION_JSON).content("{\"courier\":\"CJ대한통운\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("RESHIPPED"));
        call(patch(BASE + "/789/reship").contentType(MediaType.APPLICATION_JSON).content("{\"courier\":\"CJ대한통운\",\"trackingNo\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("수거 완료 — 반품은 환불 Saga 후 다시 조회, 교환은 수거 상태로")
    void collect() throws Exception {
        given(claimQueryService.getSeller(1L, 789L)).willReturn(claim(ClaimType.RETURN, ClaimStatus.APPROVED),
                claim(ClaimType.RETURN, ClaimStatus.COMPLETED));
        call(patch(BASE + "/789/collect")).andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("수거 완료 — 환불을 진행했습니다."))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
        verify(cancelOrderItemSaga).collectReturn(1L, 789L);
        verify(sellerClaimTx, never()).collectExchange(1L, 789L);

        given(claimQueryService.getSeller(1L, 790L)).willReturn(claim(ClaimType.EXCHANGE, ClaimStatus.APPROVED));
        given(sellerClaimTx.collectExchange(1L, 790L)).willReturn(claim(ClaimType.EXCHANGE, ClaimStatus.COLLECTED));
        call(patch(BASE + "/790/collect")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COLLECTED"));
    }

    @Test
    @DisplayName("claimId 가 숫자가 아니면 400 — 서비스 호출 없음")
    void invalidId() throws Exception {
        call(patch(BASE + "/abc/approve")).andExpect(status().isBadRequest());
        verifyNoInteractions(sellerClaimTx);
    }
}
