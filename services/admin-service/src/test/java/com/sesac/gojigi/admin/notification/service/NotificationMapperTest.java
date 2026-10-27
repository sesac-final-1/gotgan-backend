package com.sesac.gojigi.admin.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.admin.notification.dto.NewNotification;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 업무 이벤트 → 받는 쪽별 알림 (docs/events.md payload 그대로) */
class NotificationMapperTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final NotificationMapper mapper = new NotificationMapper();

    private List<NewNotification> map(String eventType, String payload) {
        return mapper.map(json.readTree("""
                {"eventId":"e-1","eventType":"%s","occurredAt":"2026-10-27T10:00:00+09:00","aggregateId":"1","version":1,"payload":%s}"""
                .formatted(eventType, payload)));
    }

    @Test
    @DisplayName("결제 완료 → 구매자에게, 상품 요약(외 n건) · 결제 금액 · 내 주문 상세 링크 · 이벤트 시각")
    void orderPaid() {
        List<NewNotification> n = map("OrderPaid", """
                {"orderNo":"20261027-000001","buyerId":4,"payAmt":59960,"items":[{"productName":"리넨 셔츠"},{"productName":"머그"}]}""");

        assertThat(n).singleElement().satisfies(x -> {
            assertThat(x.recipient()).isEqualTo("USER#4");
            assertThat(x.type()).isEqualTo("ORDER_PAID");
            assertThat(x.body()).isEqualTo("리넨 셔츠 외 1건 · 59,960원");
            assertThat(x.link()).isEqualTo("/mypage/orders/20261027-000001");
            assertThat(x.occurredAt()).isEqualTo(Instant.parse("2026-10-27T01:00:00Z"));
            assertThat(x.sortKey()).isEqualTo("1793062800000#e-1");
        });
    }

    @Test
    @DisplayName("배송 시작 · 반품 완료 → 구매자, 정산 지급 → 스토어(판매자), 입점 결과 → 신청 회원")
    void otherEvents() {
        assertThat(map("OrderItemShipped", """
                {"orderNo":"N1","buyerId":5,"productName":"머그","courier":"CJ대한통운","trackingNo":"6512"}""").getFirst().body())
                .isEqualTo("머그 · CJ대한통운 6512");
        assertThat(map("OrderItemReturned", """
                {"orderNo":"N1","buyerId":5,"productName":"머그","refundAmt":28000}""").getFirst())
                .extracting(NewNotification::recipient, NewNotification::body).containsExactly("USER#5", "머그 · 환불 28,000원");
        assertThat(map("SettlementPaid", """
                {"settlementId":42,"storeId":3,"week":"2026-W43","payoutAmt":51212}""").getFirst())
                .extracting(NewNotification::recipient, NewNotification::type, NewNotification::link)
                .containsExactly("STORE#3", "SETTLEMENT_PAID", "/seller/settlements");
        assertThat(map("StoreApplicationRejected", """
                {"applicationId":7,"userId":9,"storeName":"곳간상회","reason":"서류 불일치"}""").getFirst())
                .extracting(NewNotification::recipient, NewNotification::body).containsExactly("USER#9", "서류 불일치");
    }

    @Test
    @DisplayName("알림을 만들지 않는 이벤트(구매확정 · 스토어 상태)는 빈 목록")
    void ignored() {
        assertThat(map("OrderItemConfirmed", "{\"orderItemId\":1}")).isEmpty();
        assertThat(map("StoreStatusChanged", "{\"storeId\":1,\"status\":\"SUSPENDED\"}")).isEmpty();
    }
}
