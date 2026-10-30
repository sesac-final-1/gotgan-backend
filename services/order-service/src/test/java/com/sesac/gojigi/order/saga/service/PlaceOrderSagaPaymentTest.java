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
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.PaymentVerifyRequest;
import com.sesac.gojigi.order.order.client.dto.StockItem;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.dto.response.OrderPaymentConfirmResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 결제 확인 [요청 2] · 결제 대기 만료 · Saga 복구 — 검증 결과별 확정 · 보상 · 결제 대기 유지 */
@ExtendWith(MockitoExtension.class)
class PlaceOrderSagaPaymentTest {

    private static final Long BUYER_ID = 4L;
    private static final String ORDER_NO = "20261022-000123";
    private static final String SAGA_ID = "saga-1";
    private static final PaymentVerifyRequest VERIFY = new PaymentVerifyRequest(SAGA_ID, ORDER_NO, 65_000L);

    @Mock private OrderTxService orderTx;
    @Mock private OrderSagaTxService sagaTx;
    @Mock private UserClientAdapter user;
    @Mock private StoreClientAdapter store;
    @Mock private ProductClientAdapter product;
    @Mock private PaymentClientAdapter payment;

    @InjectMocks
    private PlaceOrderSaga saga;

    private Order order;
    private SagaInstance instance;

    /** 결제 대기 주문 — 65,000원 · 포인트 2,000 사용(OrderFixtures), 상품주문 옵션 11 × 2 */
    @BeforeEach
    void setUp() {
        order = OrderFixtures.order();
        ReflectionTestUtils.setField(order, "id", 1L);
        instance = SagaInstance.placeOrder(order, SAGA_ID);
        ReflectionTestUtils.setField(instance, "id", 7L);
        instance.advance(SagaStep.PAYMENT_READY);
    }

    private static PaymentResponse paid() {
        return new PaymentResponse(ORDER_NO, "pay-" + ORDER_NO, "PAID", 65_000L, 0L, "CARD", null);
    }

    private void givenItems() {
        given(orderTx.getItems(1L)).willReturn(List.of(OrderFixtures.item(order)));
    }

    private void givenCompensable() {
        givenItems();
        given(orderTx.claimForCompensation(1L)).willReturn(OrderStatus.FAILED);
    }

    /** 주문을 FAILED 로 선점한 뒤 전부 역순 보상 */
    private void verifyCompensatedAll() {
        InOrder back = inOrder(sagaTx, orderTx, payment, product, user);
        back.verify(sagaTx).startCompensating(eq(7L), anyString());
        back.verify(orderTx).claimForCompensation(1L);                     // ← markFailed(1L) 대신, 맨 앞으로
        back.verify(payment).cancel(ORDER_NO, PaymentCancelRequest.full(SAGA_ID, "주문 처리 실패"));
        back.verify(product).release(SAGA_ID, new StockReleaseRequest(List.of(new StockItem(11L, 2))));
        back.verify(user).restorePoints(SAGA_ID);
        back.verify(sagaTx).compensated(7L);
        verify(orderTx, never()).markFailed(any());
    }

    @Nested
    @DisplayName("결제 확인 [요청 2]")
    class Confirm {

        @BeforeEach
        void givenMine() {
            given(orderTx.getMine(BUYER_ID, ORDER_NO)).willReturn(order);
        }

        @Test
        @DisplayName("검증 성공 — 주문 금액으로 검증 → PAYMENT_VERIFIED → 주문 PAID → Saga 완료")
        void confirm_verified_paid() {
            // given
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);
            given(payment.verify(VERIFY)).willReturn(paid());

            // when
            OrderPaymentConfirmResponse response = saga.confirm(BUYER_ID, ORDER_NO);

            // then
            assertThat(response).isEqualTo(new OrderPaymentConfirmResponse(ORDER_NO, OrderStatus.PAID, 65_000L));
            InOrder steps = inOrder(payment, sagaTx, orderTx);
            steps.verify(payment).verify(VERIFY);
            steps.verify(sagaTx).advance(7L, SagaStep.PAYMENT_VERIFIED);
            steps.verify(orderTx).markPaid(1L);
            steps.verify(sagaTx).complete(7L);
        }

        @Test
        @DisplayName("이미 PAID — payment 를 부르지 않고 같은 응답 (새로고침 · 재전송)")
        void confirm_alreadyPaid_sameResponse() {
            // given
            order.markPaid(LocalDateTime.of(2026, 10, 22, 12, 0));

            // when
            OrderPaymentConfirmResponse response = saga.confirm(BUYER_ID, ORDER_NO);

            // then
            assertThat(response.status()).isEqualTo(OrderStatus.PAID);
            verifyNoInteractions(payment, sagaTx);
        }

        @Test
        @DisplayName("미결제 — 409 ORDER_PAYMENT_NOT_COMPLETED, 보상 없이 결제 대기 그대로")
        void confirm_notCompleted_keepsPending() {
            // given
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);
            willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED)).given(payment).verify(VERIFY);

            // when · then
            assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                    .extracting(e -> ((OrderException) e).getErrorCode())
                    .isEqualTo(OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED);
            verify(sagaTx, never()).startCompensating(any(), any());
            verify(orderTx, never()).markPaid(any());
        }

        @Test
        @DisplayName("payment 장애(결과 모름) — 502 그대로, 보상 없이 결제 대기 그대로")
        void confirm_paymentDown_keepsPending() {
            // given
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR)).given(payment).verify(VERIFY);

            // when · then
            assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
            verify(sagaTx, never()).startCompensating(any(), any());
        }

        @Test
        @DisplayName("금액 불일치 · 끝난 결제 — 전부 역순 보상 → 주문 FAILED 후 409 ORDER_PAYMENT_FAILED")
        void confirm_paymentFailed_compensates() {
            // given
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);
            givenItems();
            willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_FAILED)).given(payment).verify(VERIFY);

            // when · then
            assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                    .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_PAYMENT_FAILED);
            verifyCompensatedAll();
            verify(orderTx, never()).markPaid(any());
        }

        @Test
        @DisplayName("이미 실패했거나 보상 중인 주문 — 409 ORDER_INVALID_STATUS, payment 호출 없음")
        void confirm_notPending_invalidStatus() {
            // given
            order.markFailed();
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);

            // when · then
            assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                    .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATUS);
            verifyNoInteractions(payment);
        }

        @Test
        @DisplayName("주문은 PENDING 이지만 Saga 가 보상 중 — 409 ORDER_INVALID_STATUS")
        void confirm_sagaCompensating_invalidStatus() {
            // given
            instance.startCompensating("이전 실패");
            given(sagaTx.getPlaceOrder(1L)).willReturn(instance);

            // when · then
            assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                    .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATUS);
            verifyNoInteractions(payment);
        }
    }

    @Nested
    @DisplayName("결제 대기 만료")
    class Expire {

        @Test
        @DisplayName("웹훅으로 이미 결제됨 — 검증 성공이면 PAID 로 마무리")
        void expire_paidByWebhook_settles() {
            // given
            given(payment.verify(VERIFY)).willReturn(paid());

            // when
            saga.expire(instance);

            // then
            verify(orderTx).markPaid(1L);
            verify(sagaTx).complete(7L);
            verify(sagaTx, never()).startCompensating(any(), any());
        }

        @Test
        @DisplayName("끝내 미결제(결제 포기) — 전부 역순 보상 → 주문 FAILED")
        void expire_notCompleted_compensates() {
            // given
            givenItems();
            willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED)).given(payment).verify(VERIFY);

            // when
            saga.expire(instance);

            // then
            verifyCompensatedAll();
        }

        @Test
        @DisplayName("금액 불일치 — 보상은 한 번만 (settlePayment 가 보상하고 만료는 다시 하지 않음)")
        void expire_paymentFailed_compensatesOnce() {
            // given
            givenItems();
            willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_FAILED)).given(payment).verify(VERIFY);

            // when
            saga.expire(instance);

            // then
            verifyCompensatedAll();
        }

        @Test
        @DisplayName("payment 장애 · 그 밖의 주문 예외 — 아무것도 바꾸지 않고 다음 주기에 다시")
        void expire_unknown_leavesForNextRun() {
            // given
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR))
                    .willThrow(new OrderException(OrderErrorCode.ORDER_INVALID_STATUS))
                    .given(payment).verify(VERIFY);

            // when
            saga.expire(instance);
            saga.expire(instance);

            // then
            verify(sagaTx, never()).startCompensating(any(), any());
            verify(orderTx, never()).markPaid(any());
        }

        @Test
        @DisplayName("주문이 이미 PAID · FAILED — payment 를 부르지 않고 Saga 기록만 맞춘다")
        void expire_orderFinished_syncsSagaOnly() {
            // given
            order.markPaid(LocalDateTime.of(2026, 10, 22, 12, 0));
            Order failed = OrderFixtures.order();
            failed.markFailed();
            SagaInstance failedSaga = SagaInstance.placeOrder(failed, "saga-2");
            ReflectionTestUtils.setField(failedSaga, "id", 8L);

            // when
            saga.expire(instance);
            saga.expire(failedSaga);

            // then
            verify(sagaTx).complete(7L);
            verify(sagaTx).compensated(8L);
            verifyNoInteractions(payment);
        }
    }

    @Nested
    @DisplayName("Saga 복구")
    class Recover {

        @Test
        @DisplayName("멈춘 Saga · 보상 재시도 — 처음부터 전부 역순 보상")
        void recover_compensatesAll() {
            // given
            givenItems();

            // when
            saga.recover(instance);

            // then
            verifyCompensatedAll();
        }

        @Test
        @DisplayName("보상이 또 실패하면 다음 재시도 시각을 남긴다")
        void recover_compensationFails_schedulesRetry() {
            // given
            givenItems();
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "PAYMENT_CANCEL_PENDING"))
                    .given(payment).cancel(anyString(), any());

            // when
            saga.recover(instance);

            // then
            verify(sagaTx).scheduleRetry(eq(7L), anyString());
            verify(orderTx, never()).markFailed(any());
        }

        @Test
        @DisplayName("주문이 이미 PAID — 보상하지 않고 Saga 완료로 맞춘다 (0원 주문 PAID 직후 멈춘 경우)")
        void recover_orderPaid_completesSaga() {
            // given
            order.markPaid(LocalDateTime.of(2026, 10, 22, 12, 0));

            // when
            saga.recover(instance);

            // then
            verify(sagaTx).complete(7L);
            verifyNoInteractions(payment, product, user);
        }

        @Nested
        @DisplayName("결제 확인 ↔ 만료 보상 경합 (A-경-1)")
        class Race {

            @Test
            @DisplayName("만료가 미결제를 본 뒤 결제 확인이 먼저 PAID — 보상은 선점에 져서 아무것도 되돌리지 않고 Saga 완료")
            void expire_losesToConfirm_revertsNothing() {
                // given
                givenItems();
                willThrow(new OrderException(OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED)).given(payment).verify(VERIFY);
                given(orderTx.claimForCompensation(1L)).willReturn(OrderStatus.PAID);        // 그 사이 결제 확인이 이김

                // when
                saga.expire(instance);

                // then
                verify(sagaTx).complete(7L);
                verify(payment, never()).cancel(anyString(), any());                          // ← 지금 코드는 여기서 환불해 버림
                verifyNoInteractions(product, user);
                verify(sagaTx, never()).compensated(any());
            }

            @Test
            @DisplayName("보상이 먼저 선점 — 결제 확인의 PAID 는 409, Saga 를 완료로 덮지 않는다")
            void confirm_losesToCompensation_invalidStatus() {
                // given
                given(orderTx.getMine(BUYER_ID, ORDER_NO)).willReturn(order);
                given(sagaTx.getPlaceOrder(1L)).willReturn(instance);
                given(payment.verify(VERIFY)).willReturn(paid());
                willThrow(new OrderException(OrderErrorCode.ORDER_INVALID_STATUS)).given(orderTx).markPaid(1L);   // 이미 FAILED

                // when · then
                assertThatThrownBy(() -> saga.confirm(BUYER_ID, ORDER_NO))
                        .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATUS);
                verify(sagaTx, never()).complete(any());                                      // 결제 환불은 보상 쪽 payment.cancel 이 맡음
            }
        }

        @Test
        @DisplayName("Saga 복구 — 주문 FAILED(선점) + Saga COMPENSATING 이면 되돌리기를 마저 한다 (3번)")
        void recover_failedButCompensating_resumes() {
            // given
            order.markFailed();                                    // 이전 보상이 선점했지만
            instance.startCompensating("PAYMENT_CANCEL_PENDING");  // 되돌리기 도중 실패해 재시도 대기
            givenCompensable();

            // when
            saga.recover(instance);

            // then
            verifyCompensatedAll();                                // finishedAlready 가 false → 전부 되돌리고 COMPENSATED
        }
    }
}
