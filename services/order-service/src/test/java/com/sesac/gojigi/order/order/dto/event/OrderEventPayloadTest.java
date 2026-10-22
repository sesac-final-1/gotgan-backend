package com.sesac.gojigi.order.order.dto.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.order.entity.Order;
import com.sesac.gojigi.order.order.entity.OrderFixtures;
import com.sesac.gojigi.order.order.entity.OrderItem;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** order 이벤트 payload — 엔티티 스냅샷이 docs/events.md 필드로 그대로 옮겨지는지 */
class OrderEventPayloadTest {

    private final Order order = OrderFixtures.order();
    private final OrderItem item = OrderFixtures.item(order);

    @Test
    @DisplayName("OrderPaid — 주문 금액 · 주문자와 상품주문별 상품명 · 옵션 · 수량 · 금액 · 적립 포인트를 담는다")
    void orderPaid_of_mapsOrderAndItems() {
        // when
        OrderPaidPayload payload = OrderPaidPayload.of(order, List.of(item));

        // then
        assertThat(payload.orderNo()).isEqualTo("20261022-000123");
        assertThat(payload.buyerId()).isEqualTo(4L);
        assertThat(payload.ordererEmail()).isEqualTo("buyer@example.com");
        assertThat(payload.ordererName()).isEqualTo("김구매");
        assertThat(payload.payAmt()).isEqualTo(65_000L);
        assertThat(payload.usedPoint()).isEqualTo(2_000L);
        assertThat(payload.items()).containsExactly(
                new OrderPaidPayload.Item(null, 3_100L, "제철 사과 3kg", "특", 2, 64_000L));
    }

    @Test
    @DisplayName("OrderItemShipped — 주문자 · 상품 스냅샷과 송장 정보를 담는다")
    void orderItemShipped_from_mapsItemAndOrder() {
        // given
        ReflectionTestUtils.setField(item, "courier", "CJ대한통운");
        ReflectionTestUtils.setField(item, "trackingNo", "123456789012");

        // when
        OrderItemShippedPayload payload = OrderItemShippedPayload.from(item);

        // then
        assertThat(payload).isEqualTo(new OrderItemShippedPayload(null, "20261022-000123", 4L, "buyer@example.com",
                "김구매", "제철 사과 3kg", "특", "CJ대한통운", "123456789012"));
    }

    @Test
    @DisplayName("OrderItemConfirmed — 정산용 스토어 · 카테고리 스냅샷과 구매확정 시각(오프셋 +09:00)을 담는다")
    void orderItemConfirmed_from_convertsConfirmedAtToKst() {
        // given
        ReflectionTestUtils.setField(item, "confirmedAt", LocalDateTime.of(2026, 10, 29, 9, 0));

        // when
        OrderItemConfirmedPayload payload = OrderItemConfirmedPayload.from(item);

        // then
        assertThat(payload).isEqualTo(new OrderItemConfirmedPayload(null, "20261022-000123", 4L, 1L, 3L,
                "제철 사과 3kg", 301L, 3L, 64_000L, OffsetDateTime.parse("2026-10-29T09:00:00+09:00")));
    }

    @Test
    @DisplayName("OrderItemConfirmed — 구매확정 시각이 아직 없으면 null")
    void orderItemConfirmed_from_nullConfirmedAt() {
        // when · then
        assertThat(OrderItemConfirmedPayload.from(item).confirmedAt()).isNull();
    }
}
