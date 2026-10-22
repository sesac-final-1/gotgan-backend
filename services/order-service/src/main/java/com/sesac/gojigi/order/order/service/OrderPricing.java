package com.sesac.gojigi.order.order.service;

import com.sesac.gojigi.order.order.client.dto.ProductOptionResponse;
import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import com.sesac.gojigi.order.order.exception.OrderException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 주문 금액 — 브라우저가 보낸 금액은 믿지 않고 서버가 다시 계산한다 (스펙 ORD-03 · 가이드라인 06장 1단계).
 * - 상품주문 금액 = 단가(product 현재가) × 수량
 * - 배송비 = 스토어별 1회(묶음배송), 스토어 상품 합계가 무료 기준 이상이면 0
 * - 실결제금액 = 상품 합계 + 배송비 합계 - 사용 포인트 (사용 포인트는 그 합계를 넘을 수 없음)
 * - 상품주문별 사용 포인트 = 상품 합계까지의 사용 포인트를 금액 비율로 배분(나머지는 앞에서부터 1P씩) — 취소 · 반품 시 환원액.
 *   배송비를 덮은 포인트는 주문(orders.used_point)에만 남는다
 * - 상품주문별 적립 포인트 = (상품주문 금액 - 배분된 사용 포인트) × 5% (내림) — 취소 · 반품 시 회수액
 */
public final class OrderPricing {

    /** 적립률 5% — 가이드라인 06장 "포인트는 실결제금액의 5% 적립" */
    static final long EARN_RATE_PERCENT = 5L;

    private OrderPricing() {
    }

    /** 주문할 옵션 1건 — option 은 product 현재 값 */
    public record Line(ProductOptionResponse option, int quantity) {

        long amount() {
            return option.unitPrice() * quantity;
        }
    }

    public record PricedLine(Line line, long itemAmt, long usedPoint, long earnedPoint) {
    }

    public record Result(List<PricedLine> lines, Map<Long, Long> shippingFeeByStore, long totalProductAmt,
                         long totalShippingFee, long usedPoint, long payAmt) {
    }

    public static Result calculate(List<Line> lines, Function<Long, ShippingPolicy> policyOf, long usePoint) {
        long totalProductAmt = lines.stream().mapToLong(Line::amount).sum();
        Map<Long, Long> shippingFeeByStore = shippingFees(lines, policyOf);
        long totalShippingFee = shippingFeeByStore.values().stream().mapToLong(Long::longValue).sum();
        if (usePoint > totalProductAmt + totalShippingFee) {
            throw new OrderException(OrderErrorCode.ORDER_POINT_EXCEEDS_AMOUNT);
        }
        long[] allocated = allocate(lines, Math.min(usePoint, totalProductAmt), totalProductAmt);
        List<PricedLine> priced = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            long itemAmt = lines.get(i).amount();
            priced.add(new PricedLine(lines.get(i), itemAmt, allocated[i],
                    (itemAmt - allocated[i]) * EARN_RATE_PERCENT / 100));
        }
        return new Result(priced, shippingFeeByStore, totalProductAmt, totalShippingFee, usePoint,
                totalProductAmt + totalShippingFee - usePoint);
    }

    /** 스토어별 상품 합계 → 배송비 (요청 순서대로 스토어를 나열) */
    private static Map<Long, Long> shippingFees(List<Line> lines, Function<Long, ShippingPolicy> policyOf) {
        Map<Long, Long> amountByStore = new LinkedHashMap<>();
        lines.forEach(line -> amountByStore.merge(line.option().storeId(), line.amount(), Long::sum));
        Map<Long, Long> feeByStore = new LinkedHashMap<>();
        amountByStore.forEach((storeId, amount) -> feeByStore.put(storeId, policyOf.apply(storeId).feeFor(amount)));
        return feeByStore;
    }

    /** 포인트를 금액 비율로 내림 배분하고, 남은 포인트는 앞에서부터 상품주문 금액을 넘지 않게 1P씩 */
    private static long[] allocate(List<Line> lines, long points, long totalProductAmt) {
        long[] allocated = new long[lines.size()];
        if (points == 0) {
            return allocated;
        }
        long remainder = points;
        for (int i = 0; i < lines.size(); i++) {
            allocated[i] = Math.multiplyExact(points, lines.get(i).amount()) / totalProductAmt;
            remainder -= allocated[i];
        }
        for (int i = 0; remainder > 0; i = (i + 1) % lines.size()) {
            if (allocated[i] < lines.get(i).amount()) {
                allocated[i]++;
                remainder--;
            }
        }
        return allocated;
    }
}
