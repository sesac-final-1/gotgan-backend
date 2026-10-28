package com.sesac.gojigi.review.eligibility.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.common.event.EventEnvelope;
import com.sesac.gojigi.review.common.event.ProcessedEventRepository;
import com.sesac.gojigi.review.eligibility.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 작성 자격 저장 — 스냅샷 · 기한 · 재전달 · 재발행 · 동시 수신 (MySQL 8.4 + Flyway).
 * 서비스가 TransactionTemplate 으로 직접 커밋해서 테스트 트랜잭션으로 감싸지 않는다 (상품주문 · eventId 는 테스트마다 새로).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, ReviewEligibilityService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ReviewEligibilityServiceIntegrationTest {

    private static final OffsetDateTime CONFIRMED_AT = OffsetDateTime.parse("2026-10-27T10:00:00+09:00");
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(7_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    private ReviewEligibilityService reviewEligibilityService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private static EventEnvelope<OrderItemConfirmedPayload> event(String eventId, OrderItemConfirmedPayload payload) {
        return new EventEnvelope<>(eventId, "OrderItemConfirmed", CONFIRMED_AT.plusSeconds(1), "item", 1, payload);
    }

    private static OrderItemConfirmedPayload payload(long orderItemId, OffsetDateTime confirmedAt, String buyerDisplayName) {
        return new OrderItemConfirmedPayload(orderItemId, "20261027-000001", 4L, 1L, 77L, "린넨 셔츠", confirmedAt,
                buyerDisplayName, "오트밀 / M", "https://cdn.gotgan.click/products/1/a1b2.jpg");
    }

    private static String newEventId() {
        return UUID.randomUUID().toString();
    }

    @Test
    @DisplayName("자격을 저장한다 — 구매자 · 상품 스냅샷, 구매확정 시각(KST) + 30일 기한, 처리 이벤트도 함께")
    void grant_savesSnapshotAndExpiry() {
        // given
        long orderItemId = ORDER_ITEM_SEQ.incrementAndGet();
        String eventId = newEventId();

        // when
        boolean granted = reviewEligibilityService.grant(event(eventId, payload(orderItemId, CONFIRMED_AT, "곳간지기")));

        // then
        assertThat(granted).isTrue();
        ReviewEligibility saved = eligibilityRepository.findByOrderItemId(orderItemId).orElseThrow();
        assertThat(saved.getUserId()).isEqualTo(4L);
        assertThat(saved.getStoreId()).isEqualTo(1L);
        assertThat(saved.getProductId()).isEqualTo(77L);
        assertThat(saved.getBuyerDisplayName()).isEqualTo("곳간지기");
        assertThat(saved.getProductName()).isEqualTo("린넨 셔츠");
        assertThat(saved.getOptionName()).isEqualTo("오트밀 / M");
        assertThat(saved.getThumbnailUrl()).isEqualTo("https://cdn.gotgan.click/products/1/a1b2.jpg");
        assertThat(saved.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 10, 0));
        assertThat(saved.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 11, 26, 10, 0));
        assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
    }

    @Test
    @DisplayName("같은 eventId 재전달 · 다른 eventId 재발행 모두 자격은 한 번 — 처음 값 그대로")
    void grant_redeliveredOrRepublished_savesOnce() {
        // given
        long orderItemId = ORDER_ITEM_SEQ.incrementAndGet();
        String eventId = newEventId();
        reviewEligibilityService.grant(event(eventId, payload(orderItemId, CONFIRMED_AT, "곳간지기")));

        // when
        boolean redelivered = reviewEligibilityService.grant(event(eventId, payload(orderItemId, CONFIRMED_AT, "곳간지기")));
        boolean republished = reviewEligibilityService.grant(
                event(newEventId(), payload(orderItemId, CONFIRMED_AT.plusDays(1), "다른이름")));

        // then
        assertThat(redelivered).isFalse();
        assertThat(republished).isFalse();
        ReviewEligibility saved = eligibilityRepository.findByOrderItemId(orderItemId).orElseThrow();
        assertThat(saved.getBuyerDisplayName()).isEqualTo("곳간지기");
        assertThat(saved.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 10, 0));
    }

    @Test
    @DisplayName("10/28 이전 이벤트 — 구매자 표시 이름이 없으면 '구매자', 구매확정 시각이 없으면 봉투 발생 시각, 다른 오프셋은 KST 로")
    void grant_missingFieldsAndOtherOffset() {
        // given
        long noName = ORDER_ITEM_SEQ.incrementAndGet();
        long noConfirmedAt = ORDER_ITEM_SEQ.incrementAndGet();
        long utc = ORDER_ITEM_SEQ.incrementAndGet();

        // when
        reviewEligibilityService.grant(event(newEventId(), payload(noName, CONFIRMED_AT, null)));
        reviewEligibilityService.grant(event(newEventId(), payload(noConfirmedAt, null, "곳간지기")));
        reviewEligibilityService.grant(event(newEventId(), payload(utc, OffsetDateTime.parse("2026-10-27T01:00:00Z"), " ")));

        // then
        assertThat(eligibilityRepository.findByOrderItemId(noName).orElseThrow().getBuyerDisplayName())
                .isEqualTo(ReviewEligibilityService.UNKNOWN_BUYER_NAME);
        assertThat(eligibilityRepository.findByOrderItemId(noConfirmedAt).orElseThrow().getConfirmedAt())
                .isEqualTo(LocalDateTime.of(2026, 10, 27, 10, 0, 1));
        ReviewEligibility fromUtc = eligibilityRepository.findByOrderItemId(utc).orElseThrow();
        assertThat(fromUtc.getConfirmedAt()).isEqualTo(LocalDateTime.of(2026, 10, 27, 10, 0));
        assertThat(fromUtc.getBuyerDisplayName()).isEqualTo(ReviewEligibilityService.UNKNOWN_BUYER_NAME);
    }

    @Test
    @DisplayName("같은 이벤트를 동시에 5번 받으면 자격 · 처리 이벤트는 1건, 나머지는 예외 없이 건너뜀")
    void grant_concurrentSameEvent_savesOnce() throws Exception {
        // given
        long orderItemId = ORDER_ITEM_SEQ.incrementAndGet();
        EventEnvelope<OrderItemConfirmedPayload> event = event(newEventId(), payload(orderItemId, CONFIRMED_AT, "곳간지기"));
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> reviewEligibilityService.grant(event));
        }

        // when
        List<Boolean> results = new ArrayList<>();
        for (Future<Boolean> future : pool.invokeAll(tasks)) {
            results.add(future.get());
        }
        pool.shutdown();

        // then
        assertThat(results).containsOnlyOnce(true);
        assertThat(eligibilityRepository.findByOrderItemId(orderItemId)).isPresent();
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
    }
}
