package com.sesac.gojigi.order.saga.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.order.TestcontainersConfiguration;
import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import com.sesac.gojigi.order.claim.exception.ClaimErrorCode;
import com.sesac.gojigi.order.claim.repository.OrderClaimRepository;
import com.sesac.gojigi.order.claim.service.ClaimTxService;
import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.dto.request.OrderCreateRequest;
import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderItem;
import com.sesac.gojigi.order.order.entity.OrderItemHistory;
import com.sesac.gojigi.order.order.entity.OrderItemStatus;
import com.sesac.gojigi.order.order.entity.OrderStatus;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.repository.OrderItemHistoryRepository;
import com.sesac.gojigi.order.order.repository.OrderItemRepository;
import com.sesac.gojigi.order.order.repository.OrderRepository;
import com.sesac.gojigi.order.order.service.OrderPricing;
import com.sesac.gojigi.order.order.service.OrderTxService;
import com.sesac.gojigi.order.saga.entity.SagaStatus;
import com.sesac.gojigi.order.saga.entity.SagaStep;
import com.sesac.gojigi.order.saga.entity.SagaType;
import com.sesac.gojigi.order.saga.repository.SagaInstanceRepository;
import com.sesac.gojigi.order.saga.service.CancelItemTxService.CancelContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 결제완료 단계 취소 접수 · 완료 — 환불 금액(현금 · 포인트 · 배송비) · 상태 · 이력 · 접수 거절 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, OrderTxService.class, CancelItemTxService.class, OrderSagaTxService.class,
        ClaimTxService.class,
        CancelItemTxServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋
class CancelItemTxServiceIntegrationTest {

    private static final AtomicLong BUYERS = new AtomicLong(601L);

    @TestConfiguration
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-23T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired private OrderTxService orderTx;
    @Autowired private CancelItemTxService cancelTx;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderItemHistoryRepository historyRepository;
    @Autowired private OrderClaimRepository claimRepository;
    @Autowired private SagaInstanceRepository sagaInstanceRepository;
    @Autowired private OrderSagaTxService sagaTx;
    @Autowired private JdbcTemplate jdbc;

    private static OrderPricing.Line line(long optionId, long storeId, long price) {
        return new OrderPricing.Line(new ProductOptionResponse(optionId, optionId, "상품 " + optionId, null, storeId,
                301L, 3L, price, 0L, price, 100, true, null), 1);
    }

    /**
     * 결제 완료 주문 — 스토어 901: A 10,000 · B 5,000 (배송비 3,000), 스토어 902: C 20,000 (배송비 3,000).
     * 상품 35,000 + 배송비 6,000 - usePoint. 상품주문은 A · B · C 순서
     */
    private Order paidOrder(long buyerId, long usePoint) {
        OrderCreateRequest request = new OrderCreateRequest(List.of(new OrderCreateRequest.Item(91L, 1)), "김구매",
                "010-1234-5678", "06236", "서울 강남구 테헤란로 1", null, null, usePoint);
        OrderPricing.Result priced = OrderPricing.calculate(
                List.of(line(91L, 901L, 10_000L), line(92L, 901L, 5_000L), line(93L, 902L, 20_000L)),
                storeId -> new ShippingPolicy(3_000L, 50_000L), usePoint);
        Order order = orderTx.createPending(new UserSummaryResponse(buyerId, "buyer@example.com", "홍길동", null),
                request, priced, Map.of(901L, "곳간상회", 902L, "들녘마켓")).order();
        orderTx.markPaid(order.getId());
        return order;
    }

    private List<OrderItem> items(Order order) {
        return orderItemRepository.findByOrderIdOrderByIdAsc(order.getId());
    }

    private CancelContext cancel(long buyerId, Order order, OrderItem item) {
        return cancelTx.open(buyerId, order.getOrderNo(), item.getId(), ClaimReasonCode.CHANGE_OF_MIND, "다시 주문");
    }

    private static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }

    @Test
    @DisplayName("현금 결제 — 상품 금액만 환불하다가, 스토어 마지막 상품주문이면 배송비까지 · 합계는 결제 금액과 같다")
    void open_refundsItemThenShippingOnLastOfStore() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 0L);   // 결제 41,000
        List<OrderItem> items = items(order);

        // when
        CancelContext a = cancel(buyer, order, items.get(0));
        CancelContext b = cancel(buyer, order, items.get(1));
        CancelContext c = cancel(buyer, order, items.get(2));

        // then
        assertThat(a.refundAmt()).isEqualTo(10_000L);
        assertThat(b.refundAmt()).isEqualTo(5_000L + 3_000L);   // 901 마지막
        assertThat(c.refundAmt()).isEqualTo(20_000L + 3_000L);  // 902 하나뿐
        assertThat(a.refundAmt() + b.refundAmt() + c.refundAmt()).isEqualTo(order.getPayAmt());
        assertThat(List.of(a.restorePoint(), b.restorePoint(), c.restorePoint())).containsOnly(0L);
        assertThat(a.productOptionId()).isEqualTo(91L);
        assertThat(a.buyerId()).isEqualTo(buyer);
    }

    @Test
    @DisplayName("배송비까지 포인트로 낸 주문 — 남은 결제 금액까지만 현금, 나머지(배송비 몫 포함)는 포인트로 돌려준다")
    void open_pointsCoverShipping_capsCashRefund() {
        // given — 포인트 38,000 (상품 35,000 + 배송비 중 3,000), 결제 3,000
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 38_000L);
        List<OrderItem> items = items(order);

        // when
        CancelContext c = cancel(buyer, order, items.get(2));
        CancelContext a = cancel(buyer, order, items.get(0));
        CancelContext b = cancel(buyer, order, items.get(1));

        // then
        assertThat(order.getPayAmt()).isEqualTo(3_000L);
        assertThat(List.of(c.refundAmt(), a.refundAmt(), b.refundAmt())).containsExactly(3_000L, 0L, 0L);
        assertThat(List.of(c.restorePoint(), a.restorePoint(), b.restorePoint()))
                .containsExactly(20_000L, 10_000L, 5_000L + 3_000L);
        assertThat(c.restorePoint() + a.restorePoint() + b.restorePoint()).isEqualTo(38_000L);
    }

    @Test
    @DisplayName("접수 — 클레임 자동 승인 · 상품주문 CANCEL_REQUESTED · 이력 · 취소 Saga(PAYMENT_CANCEL_REQUESTED), 다시 읽어도 같은 값")
    void open_recordsClaimItemHistorySaga() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 0L);
        OrderItem item = items(order).get(0);

        // when
        CancelContext ctx = cancel(buyer, order, item);

        // then
        assertThat(claimRepository.findById(ctx.claimId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.APPROVED);
        assertThat(orderItemRepository.findById(item.getId()).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.CANCEL_REQUESTED);
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(item.getId()))
                .extracting(OrderItemHistory::getToStatus).containsExactly(OrderItemStatus.CANCEL_REQUESTED);
        var saga = sagaInstanceRepository.findById(ctx.sagaInstanceId()).orElseThrow();
        assertThat(saga.getSagaType()).isEqualTo(SagaType.CANCEL_ITEM);
        assertThat(saga.getCurrentStep()).isEqualTo(SagaStep.PAYMENT_CANCEL_REQUESTED);
        assertThat(cancelTx.load(ctx.sagaInstanceId())).isEqualTo(ctx);
    }

    @Test
    @DisplayName("완료 — 상품주문 CANCELLED · 클레임 COMPLETED · Saga 완료, 모든 상품주문이 취소되면 주문 CANCELLED")
    void finish_cancelsItemAndOrderWhenAll() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 0L);
        List<OrderItem> items = items(order);
        CancelContext a = cancel(buyer, order, items.get(0));

        // when — 하나만 끝남
        cancelTx.finish(a.sagaInstanceId());

        // then
        assertThat(orderItemRepository.findById(a.orderItemId()).orElseThrow().getStatus())
                .isEqualTo(OrderItemStatus.CANCELLED);
        assertThat(claimRepository.findById(a.claimId()).orElseThrow().getStatus()).isEqualTo(ClaimStatus.COMPLETED);
        assertThat(sagaInstanceRepository.findById(a.sagaInstanceId()).orElseThrow().getStatus())
                .isEqualTo(SagaStatus.COMPLETED);
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);

        // when — 나머지도 끝남
        cancelTx.finish(cancel(buyer, order, items.get(1)).sagaInstanceId());
        cancelTx.finish(cancel(buyer, order, items.get(2)).sagaInstanceId());

        // then
        assertThat(orderRepository.findById(order.getId()).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(historyRepository.findByOrderItemIdOrderByIdAsc(a.orderItemId()))
                .extracting(OrderItemHistory::getToStatus)
                .containsExactly(OrderItemStatus.CANCEL_REQUESTED, OrderItemStatus.CANCELLED);
    }

    @Test
    @DisplayName("복구 대상 — 재시도 시각이 지났거나 시각 없이 멈춘 취소 Saga 만 (방금 접수한 것 · 완료된 것은 제외)")
    void findCancelRetryable() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 0L);
        List<OrderItem> items = items(order);
        CancelContext due = cancel(buyer, order, items.get(0));
        sagaTx.scheduleRetry(due.sagaInstanceId(), "PAYMENT_CANCEL_PENDING");   // 12:01 (고정 시계 12:00)
        jdbc.update("update saga_instance set next_retry_at = ? where saga_instance_id = ?",
                LocalDateTime.of(2026, 10, 23, 11, 59), due.sagaInstanceId());
        CancelContext stalled = cancel(buyer, order, items.get(1));
        jdbc.update("update saga_instance set updated_at = ? where saga_instance_id = ?",
                LocalDateTime.of(2020, 1, 1, 0, 0), stalled.sagaInstanceId());
        CancelContext fresh = cancel(buyer, order, items.get(2));
        Order another = paidOrder(buyer, 0L);
        CancelContext done = cancel(buyer, another, items(another).get(0));
        cancelTx.finish(done.sagaInstanceId());
        jdbc.update("update saga_instance set updated_at = ? where saga_instance_id = ?",
                LocalDateTime.of(2020, 1, 1, 0, 0), done.sagaInstanceId());

        // when
        List<Long> found = sagaTx.findCancelRetryable(LocalDateTime.of(2021, 1, 1, 0, 0), 1_000).stream()
                .map(s -> s.getId()).toList();

        // then
        assertThat(found).contains(due.sagaInstanceId(), stalled.sagaInstanceId())
                .doesNotContain(fresh.sagaInstanceId(), done.sagaInstanceId());
    }

    @Test
    @DisplayName("접수 거절 — 다른 회원 404 · 이 주문에 없는 상품주문 404 · 진행 중 409 · 결제완료가 아님 409")
    void open_rejects() {
        // given
        long buyer = BUYERS.getAndIncrement();
        Order order = paidOrder(buyer, 0L);
        Order other = paidOrder(buyer, 0L);
        OrderItem item = items(order).get(0);
        Order pending = orderTx.createPending(new UserSummaryResponse(buyer, "b@e.com", "홍길동", null),
                new OrderCreateRequest(List.of(new OrderCreateRequest.Item(91L, 1)), "김구매", "010-1234-5678", "06236",
                        "주소", null, null, 0L),
                OrderPricing.calculate(List.of(line(91L, 901L, 10_000L)), id -> new ShippingPolicy(3_000L, 50_000L), 0L),
                Map.of(901L, "곳간상회")).order();
        cancel(buyer, order, item);

        // when · then
        assertCode(() -> cancel(buyer + 100, order, item), OrderErrorCode.ORDER_NOT_FOUND);
        assertCode(() -> cancel(buyer, order, items(other).get(0)), ClaimErrorCode.CLAIM_ITEM_NOT_FOUND);
        assertCode(() -> cancel(buyer, order, item), ClaimErrorCode.CLAIM_IN_PROGRESS);
        assertCode(() -> cancel(buyer, pending, items(pending).get(0)), ClaimErrorCode.CLAIM_NOT_CANCELLABLE);
    }
}
