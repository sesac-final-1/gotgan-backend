package com.sesac.gojigi.settlement.settlement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.settlement.TestcontainersConfiguration;
import com.sesac.gojigi.settlement.commission.entity.CategoryCommission;
import com.sesac.gojigi.settlement.commission.exception.CommissionErrorCode;
import com.sesac.gojigi.settlement.commission.repository.CategoryCommissionRepository;
import com.sesac.gojigi.settlement.common.event.ProcessedEventRepository;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.exception.SettlementErrorCode;
import com.sesac.gojigi.settlement.settlement.repository.SettlementItemRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * SQS 로 받은 봉투 JSON 을 리스너에 그대로 넣어 DB 반영까지 확인 — 실제 MySQL(Testcontainers) · Flyway V1~V5.
 * 테스트마다 다른 storeId · orderItemId 를 써서 서로 영향이 없게 한다. (폴러 자체는 SqsEventPollerTest)
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class SettlementEventListenerIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(1000);

    @Autowired StoreApprovedListener storeApprovedListener;
    @Autowired StoreStatusChangedListener storeStatusChangedListener;
    @Autowired OrderItemConfirmedListener orderItemConfirmedListener;
    @Autowired SettlementStoreRepository settlementStoreRepository;
    @Autowired SettlementItemRepository settlementItemRepository;
    @Autowired CategoryCommissionRepository categoryCommissionRepository;
    @Autowired ProcessedEventRepository processedEventRepository;

    private static String envelope(String eventId, String eventType, String occurredAt, String payloadJson) {
        return """
                {"eventId":"%s","eventType":"%s","occurredAt":"%s","aggregateId":"x","version":1,"payload":%s}
                """.formatted(eventId, eventType, occurredAt, payloadJson);
    }

    private static String storeApproved(String eventId, long storeId) {
        return envelope(eventId, "StoreApproved", "2026-10-20T10:00:00+09:00", """
                {"storeId":%d,"userId":7,"storeName":"오롯이 리넨","sellerEmail":"seller@example.com",
                 "settlementAccount":{"bank":"기업은행","number":"010-0000-8812","holder":"(주)오롯이리넨"}}
                """.formatted(storeId));
    }

    private static String itemConfirmed(String eventId, long orderItemId, long topCategoryId, long itemAmt, String confirmedAt) {
        return envelope(eventId, "OrderItemConfirmed", confirmedAt, """
                {"orderItemId":%d,"orderNo":"20261021-000123","buyerId":1042,"storeId":3,"productId":77,
                 "productName":"린넨 셔츠","categoryId":11,"topCategoryId":%d,"itemAmt":%d,"confirmedAt":"%s"}
                """.formatted(orderItemId, topCategoryId, itemAmt, confirmedAt));
    }

    private static String id() {
        return UUID.randomUUID().toString();
    }

    @Nested
    class StoreApproved {

        @Test
        @DisplayName("스냅샷 생성 — 스토어명 · 판매자 이메일 · 정산 계좌, 상태 ACTIVE")
        void storeApproved_createsSnapshot() {
            long storeId = SEQ.incrementAndGet();
            storeApprovedListener.onMessage(storeApproved(id(), storeId));

            SettlementStore store = settlementStoreRepository.findByStoreId(storeId).orElseThrow();
            assertThat(store.getStoreName()).isEqualTo("오롯이 리넨");
            assertThat(store.getSellerEmail()).isEqualTo("seller@example.com");
            assertThat(store.getBankName()).isEqualTo("기업은행");
            assertThat(store.getAccountNumber()).isEqualTo("010-0000-8812");
            assertThat(store.getAccountHolder()).isEqualTo("(주)오롯이리넨");
            assertThat(store.isPayoutHeld()).isFalse();
        }

        @Test
        @DisplayName("같은 eventId 재전달 · 같은 스토어 다른 eventId 모두 스냅샷 1건")
        void storeApproved_idempotent() {
            long storeId = SEQ.incrementAndGet();
            String eventId = id();
            storeApprovedListener.onMessage(storeApproved(eventId, storeId));
            storeApprovedListener.onMessage(storeApproved(eventId, storeId));
            storeApprovedListener.onMessage(storeApproved(id(), storeId));

            assertThat(settlementStoreRepository.findAll().stream().filter(s -> s.getStoreId() == storeId)).hasSize(1);
        }
    }

    @Nested
    class StoreStatusChanged {

        @Test
        @DisplayName("StoreApproved 보다 먼저 오면 예외(재전달) — 스냅샷이 생긴 뒤 같은 메시지는 반영")
        void statusBeforeApproved_retriesThenApplies() {
            long storeId = SEQ.incrementAndGet();
            String status = envelope(id(), "StoreStatusChanged", "2026-10-21T10:00:00+09:00",
                    "{\"storeId\":%d,\"status\":\"SUSPENDED\"}".formatted(storeId));

            assertThatThrownBy(() -> storeStatusChangedListener.onMessage(status))
                    .extracting("errorCode").isEqualTo(SettlementErrorCode.SETTLE_STORE_NOT_FOUND);

            storeApprovedListener.onMessage(storeApproved(id(), storeId));
            storeStatusChangedListener.onMessage(status);   // 재전달
            assertThat(settlementStoreRepository.findByStoreId(storeId).orElseThrow().isPayoutHeld()).isTrue();
        }
    }

    @Nested
    class OrderItemConfirmed {

        private long category;

        @BeforeEach
        void commissions() {
            category = SEQ.incrementAndGet();
            categoryCommissionRepository.save(CategoryCommission.of(category, new BigDecimal("11.00"), LocalDate.of(2026, 1, 1)));
            categoryCommissionRepository.save(CategoryCommission.of(category, new BigDecimal("12.50"), LocalDate.of(2026, 10, 26)));  // 예약분
        }

        @Test
        @DisplayName("구매확정일에 유효한 수수료율 스냅샷 · 수수료 원 단위 내림 · 상품명 · 구매확정 시각(KST)")
        void itemConfirmed_loadsWithEffectiveCommission() {
            long orderItemId = SEQ.incrementAndGet();
            orderItemConfirmedListener.onMessage(itemConfirmed(id(), orderItemId, category, 12_345L, "2026-10-21T00:12:33Z"));

            SettlementItem item = settlementItemRepository.findByOrderItemId(orderItemId).orElseThrow();
            assertThat(item.getCommissionRate()).isEqualByComparingTo("11.00");   // 10/26 예약분은 아직 아님
            assertThat(item.getCommissionAmt()).isEqualTo(1_357L);                  // 1357.95 내림
            assertThat(item.getPaymentFeeAmt()).isEqualTo(271L);                    // 271.59 내림
            assertThat(item.payoutAmt()).isEqualTo(12_345L - 1_357L - 271L);
            assertThat(item.getProductName()).isEqualTo("린넨 셔츠");
            assertThat(item.getCategoryId()).isEqualTo(category);                   // 상위 카테고리
            assertThat(item.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 21, 9, 12, 33));
            assertThat(item.getSettlementId()).isNull();                            // 집계 전
        }

        @Test
        @DisplayName("적용 시작일이 지나면 새 수수료율 — 10/26 이후 구매확정은 12.50%")
        void itemConfirmed_afterEffectiveDate_usesNewRate() {
            long orderItemId = SEQ.incrementAndGet();
            orderItemConfirmedListener.onMessage(itemConfirmed(id(), orderItemId, category, 10_000L, "2026-10-26T00:00:00+09:00"));

            assertThat(settlementItemRepository.findByOrderItemId(orderItemId).orElseThrow().getCommissionRate()).isEqualByComparingTo("12.50");
        }

        @Test
        @DisplayName("같은 eventId 재전달 · 같은 상품주문 다른 eventId 모두 1건만 적재")
        void itemConfirmed_idempotent() {
            long orderItemId = SEQ.incrementAndGet();
            String eventId = id();
            orderItemConfirmedListener.onMessage(itemConfirmed(eventId, orderItemId, category, 10_000L, "2026-10-21T09:00:00+09:00"));
            orderItemConfirmedListener.onMessage(itemConfirmed(eventId, orderItemId, category, 10_000L, "2026-10-21T09:00:00+09:00"));
            orderItemConfirmedListener.onMessage(itemConfirmed(id(), orderItemId, category, 10_000L, "2026-10-21T09:00:00+09:00"));

            assertThat(settlementItemRepository.findAll().stream().filter(i -> i.getOrderItemId() == orderItemId)).hasSize(1);
        }

        @Test
        @DisplayName("수수료율이 없으면 예외(재전달 → DLQ) — 적재 · 처리 기록 모두 남기지 않음")
        void itemConfirmed_noCommission_retriesWithoutRecord() {
            long orderItemId = SEQ.incrementAndGet();
            String eventId = id();

            assertThatThrownBy(() -> orderItemConfirmedListener.onMessage(
                    itemConfirmed(eventId, orderItemId, 999_999L, 10_000L, "2026-10-21T09:00:00+09:00")))
                    .extracting("errorCode").isEqualTo(CommissionErrorCode.COMMISSION_NOT_FOUND);
            assertThat(settlementItemRepository.existsByOrderItemId(orderItemId)).isFalse();
            assertThat(processedEventRepository.existsByEventId(eventId)).isFalse();
        }
    }
}
