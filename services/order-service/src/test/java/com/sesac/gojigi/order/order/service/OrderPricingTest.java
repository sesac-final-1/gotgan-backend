package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 주문 금액 — 스토어별 묶음배송비 · 포인트 비율 배분 · 5% 적립 */
class OrderPricingTest {

    /** 기본 배송비 3,000원 · 50,000원 이상 무료 */
    private static final Function<Long, ShippingPolicy> POLICY = storeId -> new ShippingPolicy(3_000L, 50_000L);

    static OrderPricing.Line line(long optionId, long storeId, long unitPrice, int quantity) {
        return new OrderPricing.Line(new ProductOptionResponse(optionId, optionId, "상품 " + optionId, null, storeId,
                301L, 3L, unitPrice, 0L, unitPrice, 100, true, null), quantity);
    }

    @Test
    @DisplayName("배송비는 스토어별 1회 — 스토어 상품 합계가 무료 기준 이상이면 0")
    void calculate_shippingFeePerStore() {
        // given — 스토어 1: 30,000 + 20,000 = 50,000(무료), 스토어 2: 10,000(3,000원)
        List<OrderPricing.Line> lines = List.of(line(11L, 1L, 15_000L, 2), line(12L, 1L, 20_000L, 1),
                line(21L, 2L, 10_000L, 1));

        // when
        OrderPricing.Result result = OrderPricing.calculate(lines, POLICY, 0L);

        // then
        assertThat(result.shippingFeeByStore()).containsExactly(java.util.Map.entry(1L, 0L), java.util.Map.entry(2L, 3_000L));
        assertThat(result.totalProductAmt()).isEqualTo(60_000L);
        assertThat(result.totalShippingFee()).isEqualTo(3_000L);
        assertThat(result.payAmt()).isEqualTo(63_000L);
        assertThat(result.lines()).extracting(OrderPricing.PricedLine::itemAmt).containsExactly(30_000L, 20_000L, 10_000L);
        assertThat(result.lines()).extracting(OrderPricing.PricedLine::earnedPoint).containsExactly(1_500L, 1_000L, 500L);
    }

    @Test
    @DisplayName("사용 포인트는 상품주문 금액 비율로 내림 배분하고 남은 1P 는 앞에서부터 — 적립은 배분 후 금액의 5%")
    void calculate_allocatesPointsByAmount() {
        // given — 10,000 : 20,000 에 1,001P → 333 · 667 + 나머지 1P
        List<OrderPricing.Line> lines = List.of(line(11L, 1L, 10_000L, 1), line(12L, 1L, 20_000L, 1));

        // when
        OrderPricing.Result result = OrderPricing.calculate(lines, POLICY, 1_001L);

        // then
        assertThat(result.lines()).extracting(OrderPricing.PricedLine::usedPoint).containsExactly(334L, 667L);
        assertThat(result.lines()).extracting(OrderPricing.PricedLine::earnedPoint).containsExactly(483L, 966L);
        assertThat(result.usedPoint()).isEqualTo(1_001L);
        assertThat(result.payAmt()).isEqualTo(30_000L + 3_000L - 1_001L);
    }

    @Test
    @DisplayName("배송비까지 덮는 포인트 — 상품 합계까지만 상품주문에 배분, 실결제 0원")
    void calculate_pointsCoverShipping() {
        // given — 상품 10,000 + 배송비 3,000 을 전부 포인트로
        List<OrderPricing.Line> lines = List.of(line(11L, 1L, 10_000L, 1));

        // when
        OrderPricing.Result result = OrderPricing.calculate(lines, POLICY, 13_000L);

        // then
        assertThat(result.lines().get(0).usedPoint()).isEqualTo(10_000L);
        assertThat(result.lines().get(0).earnedPoint()).isZero();
        assertThat(result.usedPoint()).isEqualTo(13_000L);
        assertThat(result.payAmt()).isZero();
    }

    @Test
    @DisplayName("사용 포인트가 상품 금액 + 배송비보다 많으면 400 ORDER_POINT_EXCEEDS_AMOUNT")
    void calculate_pointsExceedAmount_throws() {
        // given
        List<OrderPricing.Line> lines = List.of(line(11L, 1L, 10_000L, 1));

        // when · then
        assertThatThrownBy(() -> OrderPricing.calculate(lines, POLICY, 13_001L))
                .isInstanceOf(OrderException.class)
                .extracting(e -> ((OrderException) e).getErrorCode())
                .isEqualTo(OrderErrorCode.ORDER_POINT_EXCEEDS_AMOUNT);
    }
}
