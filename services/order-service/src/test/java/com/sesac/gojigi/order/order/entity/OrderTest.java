package com.sesac.gojigi.order.order.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주문 상태 전이 — PENDING 에서만 PAID · FAILED */
class OrderTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Test
    @DisplayName("PENDING → PAID 는 결제 시각을 남긴다")
    void markPaid_fromPending() {
        // given
        Order order = OrderFixtures.order();

        // when
        order.markPaid(NOW);

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("PENDING → FAILED")
    void markFailed_fromPending() {
        // given
        Order order = OrderFixtures.order();

        // when
        order.markFailed();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
    }

    @Test
    @DisplayName("PAID → CANCELLED (모든 상품주문 취소), PAID 가 아니면 409")
    void markCancelled_fromPaid() {
        // given
        Order order = OrderFixtures.order();
        order.markPaid(NOW);

        // when
        order.markCancelled();

        // then
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThatThrownBy(order::markCancelled).isInstanceOf(OrderException.class);
    }

    @Test
    @DisplayName("PENDING 이 아니면 409 ORDER_INVALID_STATUS")
    void transition_notPending_throws() {
        // given
        Order order = OrderFixtures.order();
        order.markFailed();

        // when · then
        assertThatThrownBy(() -> order.markPaid(NOW)).isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode()).isEqualTo(OrderErrorCode.ORDER_INVALID_STATUS);
        assertThatThrownBy(order::markFailed).isInstanceOf(OrderException.class);
    }
}
