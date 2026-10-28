package com.sesac.gojigi.order.order.dto.event;

import com.sesac.gojigi.order.order.entity.OrderItem;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OrderItemConfirmed payload (docs/events.md) — settlement(정산 적재) · review(작성 자격) · user(누적 구매액 · 포인트 적립).
 * itemAmt = 상품주문 금액(포인트 차감 전) = 판매자 매출. topCategoryId(상위)가 판매수수료 기준.
 * earnedPoint = 상품주문 적립 포인트(주문 시점 계산) — user 가 구매확정 때 적립 (10/27 추가, 결제 시점 적립에서 변경).
 * buyerDisplayName · optionName · thumbnailUrl = review 작성 자격 스냅샷(공개 리뷰 작성자 이름 · 마이페이지 리뷰 화면) — 10/28 추가,
 * review 는 order · user · product 를 호출하지 않는다. settlement · user 는 읽지 않음(모르는 필드 무시).
 */
public record OrderItemConfirmedPayload(Long orderItemId, String orderNo, Long buyerId, Long storeId, Long productId,
                                        String productName, Long categoryId, Long topCategoryId, long itemAmt,
                                        long earnedPoint, OffsetDateTime confirmedAt, String buyerDisplayName,
                                        String optionName, String thumbnailUrl) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderItemConfirmedPayload from(OrderItem item) {
        return new OrderItemConfirmedPayload(item.getId(), item.getOrder().getOrderNo(), item.getOrder().getBuyerId(),
                item.getStoreId(), item.getProductId(), item.getProductName(), item.getCategoryId(),
                item.getTopCategoryId(), item.getItemAmt(), item.getEarnedPoint(),
                item.getConfirmedAt() == null ? null : item.getConfirmedAt().atZone(KST).toOffsetDateTime(),
                item.getOrder().getOrdererDisplayName(), item.getOptionName(), item.getThumbnailUrl());
    }
}
