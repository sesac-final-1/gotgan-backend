package com.sesac.gojigi.user.point.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * OrderPaid payload (docs/events.md) — user 는 orderNo · buyerId · items[orderItemId, earnedPoint] 만 쓴다.
 * earnedPoint 는 order 가 정한 상품주문 적립 포인트(결제 시점 스냅샷) — user 가 다시 계산하지 않는다.
 * 주문자 이메일 · 이름 · 상품명 등은 알림 메일용이라 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderPaidPayload(String orderNo, Long buyerId, List<Item> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(Long orderItemId, long earnedPoint) {
    }
}
