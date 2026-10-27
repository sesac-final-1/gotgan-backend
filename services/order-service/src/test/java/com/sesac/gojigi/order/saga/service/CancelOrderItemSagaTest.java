package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.claim.dto.request.ClaimCreateRequest;
import com.sesac.gojigi.order.claim.dto.response.ClaimResponse;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.exception.ClaimException;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PointReversalRequest;
import com.sesac.gojigi.order.order.client.dto.StockRestoreRequest;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.service.CancelItemTxService.CancelContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 결제완료 단계 취소 Saga — 결제 부분 취소 → 재고 복원 → 포인트 환원 · 회수 → 완료, 멈추면 그 단계부터 다시 */
@ExtendWith(MockitoExtension.class)
class CancelOrderItemSagaTest {

    private static final String ORDER_NO = "20261023-000001";
    private static final ClaimCreateRequest CANCEL = new ClaimCreateRequest(456L, ClaimType.CANCEL,
            ClaimReasonCode.CHANGE_OF_MIND, "다시 주문");

    @Mock private CancelItemTxService cancelTx;
    @Mock private OrderSagaTxService sagaTx;
    @Mock private PaymentClientAdapter payment;
    @Mock private ProductClientAdapter product;
    @Mock private UserClientAdapter user;

    @InjectMocks
    private CancelOrderItemSaga saga;

    /** 환불 8,000 · 포인트 2,000 환원 · 적립 300 회수 · 옵션 11 × 2 */
    private static CancelContext ctx(long refundAmt) {
        return new CancelContext(7L, "saga-c1", 789L, ClaimType.CANCEL, 456L, ORDER_NO, 4L, 11L, 2, refundAmt, 2_000L,
                300L);
    }

    private void givenOpened(long refundAmt) {
        given(cancelTx.open(4L, ORDER_NO, 456L, ClaimReasonCode.CHANGE_OF_MIND, "다시 주문")).willReturn(ctx(refundAmt));
    }

    @Test
    @DisplayName("끝까지 — 결제 부분 취소 → 재고 복원 → 포인트 환원 · 회수 → 완료, 단계마다 기록 · COMPLETED 응답")
    void request_allSteps_completed() {
        // given
        givenOpened(8_000L);

        // when
        ClaimResponse response = saga.request(4L, ORDER_NO, CANCEL);

        // then
        assertThat(response).isEqualTo(new ClaimResponse(789L, 456L, ClaimType.CANCEL, ClaimStatus.COMPLETED,
                8_000L, 2_000L));
        InOrder steps = inOrder(payment, product, user, sagaTx, cancelTx);
        steps.verify(payment).cancel(ORDER_NO, PaymentCancelRequest.partial("saga-c1", 456L, 8_000L, "구매자 취소"));
        steps.verify(sagaTx).advance(7L, SagaStep.PAYMENT_CANCELLED);
        steps.verify(product).restore(new StockRestoreRequest(789L, 11L, 2));
        steps.verify(sagaTx).advance(7L, SagaStep.STOCK_RESTORED);
        steps.verify(user).reversePoints(new PointReversalRequest(789L, 456L, 4L, ORDER_NO, 2_000L, 300L));
        steps.verify(sagaTx).advance(7L, SagaStep.POINT_RESTORED);
        steps.verify(cancelTx).finish(7L);
    }

    @Test
    @DisplayName("환불할 현금이 0(전액 포인트) — 결제 취소 없이 다음 단계로")
    void request_zeroRefund_skipsPayment() {
        givenOpened(0L);

        ClaimResponse response = saga.request(4L, ORDER_NO, CANCEL);

        assertThat(response.status()).isEqualTo(ClaimStatus.COMPLETED);
        verifyNoInteractions(payment);
        verify(sagaTx).advance(7L, SagaStep.PAYMENT_CANCELLED);
    }

    @Test
    @DisplayName("결제 취소 미확정(409 PAYMENT_CANCEL_PENDING · 장애) — 재고 · 포인트를 건드리지 않고 재시도 기록, APPROVED 응답")
    void request_paymentCancelPending_stopsAndSchedulesRetry() {
        // given
        givenOpened(8_000L);
        willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "PAYMENT_CANCEL_PENDING"))
                .given(payment).cancel(anyString(), any());

        // when
        ClaimResponse response = saga.request(4L, ORDER_NO, CANCEL);

        // then
        assertThat(response.status()).isEqualTo(ClaimStatus.APPROVED);
        verify(sagaTx).scheduleRetry(eq(7L), anyString());
        verify(sagaTx, never()).advance(any(), any());
        verifyNoInteractions(product, user);
        verify(cancelTx, never()).finish(any());
    }

    @Test
    @DisplayName("복구 — 멈춘 단계(재고 복원 뒤)부터 이어서, 앞 단계는 다시 부르지 않음")
    void resume_fromStockRestored() {
        // given
        SagaInstance instance = SagaInstance.placeOrder(OrderFixtures.order(), "saga-c1");
        ReflectionTestUtils.setField(instance, "id", 7L);
        instance.advance(SagaStep.STOCK_RESTORED);
        given(cancelTx.load(7L)).willReturn(ctx(8_000L));

        // when
        saga.resume(instance);

        // then
        verifyNoInteractions(payment, product);
        verify(user).reversePoints(any());
        verify(cancelTx).finish(7L);
    }

    @Test
    @DisplayName("복구 중 또 실패하면 다음 재시도 시각을 남긴다")
    void resume_failsAgain_schedulesRetry() {
        // given
        SagaInstance instance = SagaInstance.placeOrder(OrderFixtures.order(), "saga-c1");
        ReflectionTestUtils.setField(instance, "id", 7L);
        instance.advance(SagaStep.PAYMENT_CANCELLED);
        given(cancelTx.load(7L)).willReturn(ctx(8_000L));
        willThrow(new IllegalStateException("product down")).given(product).restore(any());

        // when
        saga.resume(instance);

        // then
        verify(sagaTx).scheduleRetry(7L, "IllegalStateException: product down");
        verifyNoInteractions(user);
    }

    @Test
    @DisplayName("반품 · 교환은 아직 — 400 CLAIM_TYPE_NOT_SUPPORTED, 접수하지 않음")
    void request_returnNotSupported() {
        assertThatThrownBy(() -> saga.request(4L, ORDER_NO,
                new ClaimCreateRequest(456L, ClaimType.RETURN, ClaimReasonCode.DEFECTIVE, null)))
                .isInstanceOf(ClaimException.class)
                .extracting(e -> ((ClaimException) e).getErrorCode()).isEqualTo(ClaimErrorCode.CLAIM_TYPE_NOT_SUPPORTED);
        verifyNoInteractions(cancelTx);
    }
}
