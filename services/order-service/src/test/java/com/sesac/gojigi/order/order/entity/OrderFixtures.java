package com.sesac.gojigi.order.order.entity;

import java.util.concurrent.atomic.AtomicLong;

/** 테스트용 주문 · 상품주문 — 이벤트 payload 값 확인에 쓰는 스냅샷 값 */
public final class OrderFixtures {

    private static final AtomicLong ORDER_NO = new AtomicLong();

    private OrderFixtures() {
    }

    /** 테스트마다 겹치지 않는 주문번호 — 채번은 OrderNoIssuerIntegrationTest 에서 따로 확인 */
    public static String nextOrderNo() {
        return String.format("20261022-%06d", ORDER_NO.incrementAndGet());
    }

    public static Order order() {
        return Order.builder()
                .buyerId(4L).orderNo("20261022-000123")
                .ordererName("김구매").ordererDisplayName("곳간지기").ordererEmail("buyer@example.com")
                .receiverName("김구매").receiverPhone("010-1234-5678").zipcode("06236")
                .address1("서울 강남구 테헤란로 1").address2("101호").deliveryMemo("문 앞")
                .totalProductAmt(64_000L).totalShippingFee(3_000L).usedPoint(2_000L).payAmt(65_000L)
                .build();
    }

    public static OrderItem item(Order order) {
        return OrderItem.builder()
                .order(order).storeId(1L).productId(3L).productOptionId(11L).categoryId(301L).topCategoryId(3L)
                .itemNo("20261022-000123-01").storeName("곳간상회").productName("제철 사과 3kg").optionName("특")
                .thumbnailUrl("https://cdn.gotgan.click/products/1/apple.jpg")
                .unitPrice(32_000L).quantity(2).itemAmt(64_000L).usedPoint(2_000L).earnedPoint(3_100L)
                .build();
    }
}
