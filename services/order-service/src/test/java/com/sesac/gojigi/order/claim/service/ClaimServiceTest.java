package com.sesac.gojigi.order.claim.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimPreviewResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.service.ClaimTxService.ClaimTarget;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.entity.OrderItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 환불 예정액 · 반품 · 교환 신청 — 유형 · 사유별 금액, 판매자 귀책이면 store 를 부르지 않음 */
@ExtendWith(MockitoExtension.class)
class ClaimServiceTest {

    private static final String ORDER_NO = "20261022-000123";
    /** 스토어 1 — 기본 배송비 3,000원 */
    private static final StoreResponse STORE = new StoreResponse(1L, 2L, "곳간상회", "ACTIVE", 3_000L, 50_000L);

    @Mock private ClaimTxService claimTx;
    @Mock private StoreClientAdapter store;

    @InjectMocks
    private ClaimService claimService;

    /** 상품주문 64,000 · 사용 포인트 2,000 · 적립 3,100 · 스토어 1 (OrderFixtures) */
    private ClaimTarget target;

    @BeforeEach
    void setUp() {
        Order order = OrderFixtures.order();
        OrderItem item = OrderFixtures.item(order);
        ReflectionTestUtils.setField(item, "id", 456L);
        target = new ClaimTarget(order, item, 65_000L, 3_000L);
    }

    private ClaimPreviewResponse preview(ClaimType type, ClaimReasonCode reason) {
        given(claimTx.find(4L, ORDER_NO, 456L, type)).willReturn(target);
        return claimService.preview(4L, ORDER_NO, 456L, type, reason);
    }

    @Test
    @DisplayName("취소 — 스토어 마지막이면 배송비까지 돌려받고, 적립 포인트는 회수 예정")
    void preview_cancel() {
        assertThat(preview(ClaimType.CANCEL, ClaimReasonCode.CHANGE_OF_MIND)).isEqualTo(new ClaimPreviewResponse(
                456L, ClaimType.CANCEL, ClaimReasonCode.CHANGE_OF_MIND, 64_000L, 3_000L, false, 65_000L, 2_000L, 0L));   // 구매확정 전 — 회수할 적립 없음
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("반품 단순변심 — 스토어 기본 배송비 × 2 를 빼고 환불")
    void preview_returnChangeOfMind() {
        given(store.getStore(1L)).willReturn(STORE);

        ClaimPreviewResponse result = preview(ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND);

        assertThat(result.shippingFee()).isEqualTo(-6_000L);
        assertThat(result.refundAmt()).isEqualTo(64_000L - 2_000L - 6_000L);
        assertThat(result.restorePoint()).isEqualTo(2_000L);
        assertThat(result.sellerFault()).isFalse();
    }

    @Test
    @DisplayName("반품 불량 — 판매자 부담이라 차감 없음 · store 를 부르지 않음")
    void preview_returnDefective() {
        ClaimPreviewResponse result = preview(ClaimType.RETURN, ClaimReasonCode.DEFECTIVE);

        assertThat(result.shippingFee()).isZero();
        assertThat(result.refundAmt()).isEqualTo(62_000L);
        assertThat(result.sellerFault()).isTrue();
        verifyNoInteractions(store);
    }

    @Test
    @DisplayName("교환 — 결제 · 포인트 변동 없음")
    void preview_exchange() {
        ClaimPreviewResponse result = preview(ClaimType.EXCHANGE, ClaimReasonCode.WRONG_DELIVERY);

        assertThat(result.refundAmt()).isZero();
        assertThat(result.restorePoint()).isZero();
        assertThat(result.reclaimPoint()).isZero();
        assertThat(result.shippingFee()).isZero();
    }

    @Test
    @DisplayName("반품 신청은 계산한 반품 배송비를, 교환 신청은 0 을 넘겨 접수")
    void requestReturnOrExchange_passesFee() {
        ClaimCreateRequest ret = new ClaimCreateRequest(456L, ClaimType.RETURN, ClaimReasonCode.CHANGE_OF_MIND, null);
        ClaimCreateRequest exchange = new ClaimCreateRequest(456L, ClaimType.EXCHANGE, ClaimReasonCode.CHANGE_OF_MIND, null);
        given(claimTx.find(4L, ORDER_NO, 456L, ClaimType.RETURN)).willReturn(target);
        given(claimTx.find(4L, ORDER_NO, 456L, ClaimType.EXCHANGE)).willReturn(target);
        given(store.getStore(1L)).willReturn(STORE);

        claimService.requestReturnOrExchange(4L, ORDER_NO, ret);
        claimService.requestReturnOrExchange(4L, ORDER_NO, exchange);

        verify(claimTx).openReturnOrExchange(4L, ORDER_NO, ret, 6_000L);
        verify(claimTx).openReturnOrExchange(4L, ORDER_NO, exchange, 0L);
    }
}
