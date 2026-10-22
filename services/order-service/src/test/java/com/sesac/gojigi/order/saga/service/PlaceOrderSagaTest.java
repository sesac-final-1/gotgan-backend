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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.order.order.client.PaymentClientAdapter;
import com.sesac.gojigi.order.order.client.ProductClientAdapter;
import com.sesac.gojigi.order.order.client.ShippingPolicyClient;
import com.sesac.gojigi.order.order.client.StoreClientAdapter;
import com.sesac.gojigi.order.order.client.UserClientAdapter;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.PointUseRequest;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.StockItem;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.dto.response.OrderCreateResponse;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.order.service.OrderTxService.PendingOrder;
import com.sesac.gojigi.order.saga.entity.SagaInstance;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

/** 주문 생성 Saga — 단계 순서 · 0원 주문 · 실패 시 역순 보상 · 보상 실패 시 재시도 기록 */
@ExtendWith(MockitoExtension.class)
class PlaceOrderSagaTest {

    private static final Long BUYER_ID = 4L;
    private static final String SAGA_ID = "saga-1";
    private static final List<StockItem> STOCK = List.of(new StockItem(11L, 2));

    @Mock private OrderTxService orderTx;
    @Mock private SagaStore sagaStore;
    @Mock private UserClientAdapter user;
    @Mock private StoreClientAdapter store;
    @Mock private ShippingPolicyClient shippingPolicy;
    @Mock private ProductClientAdapter product;
    @Mock private PaymentClientAdapter payment;

    @InjectMocks
    private PlaceOrderSaga saga;

    private Order order;

    private static OrderCreateRequest request(long usePoint, OrderCreateRequest.Item... items) {
        return new OrderCreateRequest(List.of(items), "김구매", "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null,
                null, usePoint);
    }

    private static OrderCreateRequest.Item item(long optionId, int quantity) {
        return new OrderCreateRequest.Item(optionId, quantity);
    }

    private static ProductOptionResponse option(long optionId, boolean purchasable) {
        return new ProductOptionResponse(optionId, 3L, "제철 사과 3kg", "특", 1L, 301L, 3L, 32_000L, 0L, 32_000L, 10,
                purchasable, null);
    }

    /** 주문 64,000 + 배송비 0(5만 이상 무료) — usePoint 에 따라 payAmt 가 정해진 PENDING 주문 */
    private void givenPending(long usePoint) {
        order = Order.builder().buyerId(BUYER_ID).orderNo("20261022-000123").ordererName("김구매")
                .ordererDisplayName("김*매").ordererEmail("buyer@example.com").receiverName("김구매")
                .receiverPhone("010-1234-5678").zipcode("06236").address1("서울 강남구 테헤란로 1")
                .totalProductAmt(64_000L).totalShippingFee(0L).usedPoint(usePoint).payAmt(64_000L - usePoint).build();
        ReflectionTestUtils.setField(order, "id", 1L);
        OrderItem item = OrderFixtures.item(order);
        SagaInstance instance = SagaInstance.placeOrder(order, SAGA_ID);
        ReflectionTestUtils.setField(instance, "id", 7L);
        given(product.getOptions(List.of(11L))).willReturn(List.of(option(11L, true)));
        given(user.getUser(BUYER_ID)).willReturn(new UserSummaryResponse(BUYER_ID, "buyer@example.com", "김구매", null));
        given(store.getStore(1L)).willReturn(new StoreResponse(1L, 2L, "곳간상회", "ACTIVE"));
        given(shippingPolicy.getShippingPolicy(1L)).willReturn(new ShippingPolicy(3_000L, 50_000L));
        given(orderTx.createPending(any(), any(), any(), any())).willReturn(new PendingOrder(order, List.of(item)));
        given(sagaStore.startPlaceOrder(order)).willReturn(instance);
    }

    @Nested
    @DisplayName("성공")
    class Success {

        @Test
        @DisplayName("포인트 사용 → 재고 예약 → 결제 준비 순서로 부르고 결제창 정보를 돌려준다")
        void place_withPoints_allSteps() {
            // given
            givenPending(2_000L);
            given(payment.prepare(any())).willReturn(new PaymentResponse("20261022-000123", "pay-20261022-000123",
                    "READY", 62_000L, 0L, null, null));

            // when
            OrderCreateResponse response = saga.place(BUYER_ID, request(2_000L, item(11L, 2)));

            // then
            assertThat(response).isEqualTo(new OrderCreateResponse("20261022-000123", OrderStatus.PENDING, 62_000L,
                    "pay-20261022-000123", "제철 사과 3kg"));
            InOrder steps = inOrder(user, product, payment, sagaStore);
            steps.verify(user).usePoints(new PointUseRequest(SAGA_ID, BUYER_ID, "20261022-000123", 2_000L));
            steps.verify(sagaStore).advance(7L, SagaStep.POINT_USED);
            steps.verify(product).reserve(new StockReserveRequest(SAGA_ID, STOCK));
            steps.verify(sagaStore).advance(7L, SagaStep.STOCK_RESERVED);
            steps.verify(payment).prepare(any());
            steps.verify(sagaStore).advance(7L, SagaStep.PAYMENT_READY);
        }

        @Test
        @DisplayName("포인트를 안 쓰면 user 포인트 사용을 부르지 않는다")
        void place_withoutPoints_skipsPointStep() {
            // given
            givenPending(0L);
            given(payment.prepare(any())).willReturn(new PaymentResponse("20261022-000123", "pay-20261022-000123",
                    "READY", 64_000L, 0L, null, null));

            // when
            saga.place(BUYER_ID, request(0L, item(11L, 2)));

            // then
            verify(user, never()).usePoints(any());
            verify(sagaStore, never()).advance(7L, SagaStep.POINT_USED);
        }

        @Test
        @DisplayName("전액 포인트(0원)는 결제 준비 없이 바로 PAID · Saga 완료")
        void place_zeroPayAmount_paidWithoutPayment() {
            // given
            givenPending(64_000L);

            // when
            OrderCreateResponse response = saga.place(BUYER_ID, request(64_000L, item(11L, 2)));

            // then
            assertThat(response.status()).isEqualTo(OrderStatus.PAID);
            assertThat(response.portonePaymentId()).isNull();
            verify(orderTx).markPaid(1L);
            verify(sagaStore).complete(7L);
            verifyNoInteractions(payment);
        }

        @Test
        @DisplayName("주문번호가 동시 주문과 겹치면 다시 발급한다")
        void place_orderNoCollision_retries() {
            // given
            givenPending(0L);
            given(orderTx.createPending(any(), any(), any(), any()))
                    .willThrow(new DataIntegrityViolationException("uk_orders_order_no"))
                    .willReturn(new PendingOrder(order, List.of(OrderFixtures.item(order))));
            given(payment.prepare(any())).willReturn(new PaymentResponse("20261022-000123", "pay-1", "READY",
                    64_000L, 0L, null, null));

            // when
            saga.place(BUYER_ID, request(0L, item(11L, 2)));

            // then
            verify(orderTx, times(2)).createPending(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("주문 전 검증 — 주문 · Saga 를 만들지 않는다")
    class BeforeOrder {

        @Test
        @DisplayName("같은 옵션이 두 번이면 400 INVALID_INPUT")
        void place_duplicateOption_invalidInput() {
            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(0L, item(11L, 1), item(11L, 2))))
                    .isInstanceOf(BusinessException.class)
                    .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.INVALID_INPUT);
            verifyNoInteractions(product, orderTx, sagaStore);
        }

        @Test
        @DisplayName("product 응답에 없거나 구매 불가인 옵션이 있으면 409 ORDER_PRODUCT_NOT_PURCHASABLE")
        void place_notPurchasable() {
            // given — 11 은 구매 불가, 12 는 응답에서 빠짐
            given(product.getOptions(List.of(11L))).willReturn(List.of(option(11L, false)));
            given(product.getOptions(List.of(12L))).willReturn(List.of());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(0L, item(11L, 1))))
                    .extracting(e -> ((OrderException) e).getErrorCode())
                    .isEqualTo(OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(0L, item(12L, 1))))
                    .isInstanceOf(OrderException.class);
            verifyNoInteractions(orderTx, sagaStore);
        }
    }

    @Nested
    @DisplayName("실패 — 시도한 단계만 역순 보상 후 주문 FAILED")
    class Compensation {

        @Test
        @DisplayName("재고 예약 실패 — 재고 해제 · 포인트 환원, 결제 취소는 부르지 않음 · 결제창 정보 없이 409")
        void place_outOfStock_compensatesStockAndPoints() {
            // given
            givenPending(2_000L);
            willThrow(new OrderException(OrderErrorCode.ORDER_OUT_OF_STOCK)).given(product).reserve(any());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(2_000L, item(11L, 2))))
                    .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_OUT_OF_STOCK);
            InOrder back = inOrder(sagaStore, product, user, orderTx);
            back.verify(sagaStore).startCompensating(eq(7L), anyString());
            back.verify(product).release(SAGA_ID, new StockReleaseRequest(STOCK));
            back.verify(user).restorePoints(SAGA_ID);
            back.verify(orderTx).markFailed(1L);
            back.verify(sagaStore).compensated(7L);
            verify(payment, never()).prepare(any());
            verify(payment, never()).cancel(anyString(), any());
        }

        @Test
        @DisplayName("포인트 부족 — 포인트 단계까지만 되돌림(재고 해제 없음)")
        void place_pointInsufficient_compensatesPointOnly() {
            // given
            givenPending(2_000L);
            willThrow(new OrderException(OrderErrorCode.ORDER_POINT_INSUFFICIENT)).given(user).usePoints(any());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(2_000L, item(11L, 2))))
                    .isInstanceOf(OrderException.class);
            verify(user).restorePoints(SAGA_ID);
            verify(product, never()).reserve(any());
            verify(product, never()).release(anyString(), any());
            verify(orderTx).markFailed(1L);
        }

        @Test
        @DisplayName("결제 준비 실패(결과 모름) — 결제 전액 취소 → 재고 해제 → 포인트 환원 순서")
        void place_paymentPrepareFails_compensatesAll() {
            // given
            givenPending(2_000L);
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR)).given(payment).prepare(any());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(2_000L, item(11L, 2))))
                    .isInstanceOf(BusinessException.class);
            InOrder back = inOrder(payment, product, user, orderTx);
            back.verify(payment).cancel("20261022-000123", PaymentCancelRequest.full(SAGA_ID, "주문 처리 실패"));
            back.verify(product).release(SAGA_ID, new StockReleaseRequest(STOCK));
            back.verify(user).restorePoints(SAGA_ID);
            back.verify(orderTx).markFailed(1L);
        }

        @Test
        @DisplayName("보상 호출도 실패하면 주문은 그대로 두고 Saga 를 재시도 대상으로 남긴다")
        void place_compensationFails_schedulesRetry() {
            // given
            givenPending(0L);
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR)).given(payment).prepare(any());
            willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "PAYMENT_CANCEL_PENDING"))
                    .given(payment).cancel(anyString(), any());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(0L, item(11L, 2))))
                    .isInstanceOf(BusinessException.class);
            verify(sagaStore).scheduleRetry(eq(7L), anyString());
            verify(orderTx, never()).markFailed(any());
            verify(sagaStore, never()).compensated(any());
        }

        @Test
        @DisplayName("예상 못 한 예외도 보상한다 — 원인은 예외 클래스 이름으로 남김")
        void place_unexpectedException_compensates() {
            // given
            givenPending(0L);
            willThrow(new IllegalStateException("boom")).given(product).reserve(any());

            // when · then
            assertThatThrownBy(() -> saga.place(BUYER_ID, request(0L, item(11L, 2))))
                    .isInstanceOf(IllegalStateException.class);
            verify(sagaStore).startCompensating(7L, "IllegalStateException: boom");
            verify(orderTx).markFailed(1L);
        }
    }

    @Test
    @DisplayName("주문명 — 상품주문이 여러 개면 '첫 상품 외 n건'")
    void orderName_multipleItems() {
        // given
        Order o = OrderFixtures.order();

        // when · then
        assertThat(PlaceOrderSaga.orderName(List.of(OrderFixtures.item(o), OrderFixtures.item(o), OrderFixtures.item(o))))
                .isEqualTo("제철 사과 3kg 외 2건");
    }
}
