package com.sesac.gojigi.admin.notification.service;

import com.sesac.gojigi.admin.notification.dto.NewNotification;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * 업무 이벤트 봉투 → 받는 쪽별 알림 (docs/events.md 의 payload 필드 그대로). 알림을 만들지 않는 이벤트면 빈 목록.
 * 문구는 화면 알림함 · 헤더 벨에 그대로 보인다 — 메일(order-notification Lambda)보다 짧게.
 */
@Component
public class NotificationMapper {

    private static final NumberFormat WON = NumberFormat.getNumberInstance(Locale.KOREA);

    public List<NewNotification> map(JsonNode envelope) {
        String eventId = envelope.path("eventId").asString();
        String eventType = envelope.path("eventType").asString("");
        Instant at = occurredAt(envelope.path("occurredAt").asString(null));
        JsonNode p = envelope.path("payload");
        return switch (eventType) {
            case "OrderPaid" -> List.of(toUser(p, eventId, at, "ORDER_PAID", "결제가 완료됐어요",
                    itemsSummary(p) + " · " + won(p.path("payAmt").asLong()), orderLink(p)));
            case "OrderItemShipped" -> List.of(toUser(p, eventId, at, "ORDER_SHIPPED", "배송이 시작됐어요",
                    p.path("productName").asString() + " · " + p.path("courier").asString() + " " + p.path("trackingNo").asString(), orderLink(p)));
            case "OrderItemCancelled" -> List.of(toUser(p, eventId, at, "ORDER_CANCELLED", "취소가 완료됐어요",
                    p.path("productName").asString() + " · 환불 " + won(p.path("refundAmt").asLong()), orderLink(p)));
            case "OrderItemReturned" -> List.of(toUser(p, eventId, at, "ORDER_RETURNED", "반품이 완료됐어요",
                    p.path("productName").asString() + " · 환불 " + won(p.path("refundAmt").asLong()), orderLink(p)));
            case "SettlementPaid" -> List.of(new NewNotification("STORE#" + p.path("storeId").asLong(), eventId, "SETTLEMENT_PAID",
                    "정산금이 지급됐어요", p.path("week").asString() + " · " + won(p.path("payoutAmt").asLong()), "/seller/settlements", at));
            case "StoreApproved" -> List.of(toUser(p, eventId, at, "STORE_APPROVED", "입점이 승인됐어요",
                    p.path("storeName").asString() + " — 다시 로그인하면 판매자센터를 쓸 수 있어요", "/seller"));
            case "StoreApplicationRejected" -> List.of(toUser(p, eventId, at, "STORE_REJECTED", "입점 신청이 반려됐어요",
                    p.path("reason").asString(""), "/seller/apply"));
            case "StoreApplicationSupplementRequested" -> List.of(toUser(p, eventId, at, "STORE_SUPPLEMENT", "서류 보완 요청이 왔어요",
                    p.path("message").asString(""), "/seller/apply"));
            default -> List.of();
        };
    }

    private static NewNotification toUser(JsonNode p, String eventId, Instant at, String type, String title, String body, String link) {
        return new NewNotification("USER#" + p.path(p.has("buyerId") ? "buyerId" : "userId").asLong(), eventId, type, title, body, link, at);
    }

    /** 주문 상품 요약 — "리넨 셔츠 외 2건" */
    private static String itemsSummary(JsonNode p) {
        JsonNode items = p.path("items");
        String first = items.path(0).path("productName").asString("주문 상품");
        return items.size() > 1 ? first + " 외 " + (items.size() - 1) + "건" : first;
    }

    private static String orderLink(JsonNode p) {
        return "/mypage/orders/" + p.path("orderNo").asString();
    }

    private static String won(long amount) {
        return WON.format(amount) + "원";
    }

    /** 봉투 시각(오프셋 포함 ISO-8601) — 없거나 형식이 다르면 지금 */
    private static Instant occurredAt(String value) {
        try {
            return value == null ? Instant.now() : OffsetDateTime.parse(value).toInstant();
        } catch (RuntimeException e) {
            return Instant.now();
        }
    }
}
