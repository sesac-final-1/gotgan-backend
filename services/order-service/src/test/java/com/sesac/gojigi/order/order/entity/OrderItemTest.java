package com.sesac.gojigi.order.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 상품주문 상태 전이 — 주문완료(ORDERED)로 시작해 결제 완료 때만 PAYMENT_DONE */
class OrderItemTest {

    @Test
    @DisplayName("새 상품주문은 ORDERED, 결제 완료면 PAYMENT_DONE")
    void markPaymentDone_fromOrdered() {
        // given
        OrderItem item = OrderFixtures.item(OrderFixtures.order());
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.ORDERED);

        // when
        item.markPaymentDone();

        // then
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.PAYMENT_DONE);
    }

    @Test
    @DisplayName("취소 — 결제완료에서만 접수(CANCEL_REQUESTED), 접수된 것만 취소 완료(CANCELLED)")
    void cancel_transitions() {
        // given
        OrderItem item = OrderFixtures.item(OrderFixtures.order());
        item.markPaymentDone();

        // when
        item.requestCancel();
        item.cancelled();

        // then
        assertThat(item.getStatus()).isEqualTo(OrderItemStatus.CANCELLED);
        assertThatThrownBy(item::requestCancel).isInstanceOf(OrderException.class);
        assertThatThrownBy(OrderFixtures.item(OrderFixtures.order())::cancelled).isInstanceOf(OrderException.class);
    }

    @Test
    @DisplayName("반품 · 교환 신청 — 배송완료에서만")
    void returnAndExchange_onlyFromDelivered() {
        // given
        OrderItem returning = OrderFixtures.item(OrderFixtures.order());
        OrderItem exchanging = OrderFixtures.item(OrderFixtures.order());
        ReflectionTestUtils.setField(returning, "status", OrderItemStatus.DELIVERED);
        ReflectionTestUtils.setField(exchanging, "status", OrderItemStatus.DELIVERED);

        // when
        returning.requestReturn();
        exchanging.requestExchange();

        // then
        assertThat(returning.getStatus()).isEqualTo(OrderItemStatus.RETURN_REQUESTED);
        assertThat(exchanging.getStatus()).isEqualTo(OrderItemStatus.EXCHANGE_REQUESTED);
        assertThatThrownBy(OrderFixtures.item(OrderFixtures.order())::requestReturn).isInstanceOf(OrderException.class);
    }

    @Test
    @DisplayName("ORDERED 가 아니면 409 ORDER_INVALID_STATUS")
    void markPaymentDone_twice_throws() {
        // given
        OrderItem item = OrderFixtures.item(OrderFixtures.order());
        item.markPaymentDone();

        // when · then
        assertThatThrownBy(item::markPaymentDone).isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATUS);
    }
}
