package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.point.service.PointService;
import com.sesac.gojigi.user.user.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 구매확정 반영 — 실제 MySQL. 누적 구매확정 금액 + 포인트 적립이 한 번에, eventId · 상품주문 멱등, 탈퇴 회원, 같은 이벤트 동시 수신.
 * 서비스가 TransactionTemplate 으로 직접 커밋해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, PurchaseConfirmedService.class, PointService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PurchaseConfirmedServiceIntegrationTest {

    private static final String ORDER_NO = "20261027-000001";

    @Autowired private PurchaseConfirmedService purchaseConfirmedService;
    @Autowired private UserRepository userRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        pointHistoryRepository.deleteAllInBatch();
        processedEventRepository.findAll().stream()
                .filter(e -> eventIds.contains(e.getEventId()))
                .forEach(processedEventRepository::delete);
        userRepository.deleteAllInBatch();
    }

    private Long member() {
        return userRepository.save(User.signUp("cf-" + System.nanoTime() + "@gotgan.com", "확정", null, null)).getId();
    }

    private EventEnvelope<OrderItemConfirmedPayload> confirmed(Long userId, long orderItemId, long itemAmt, long earnedPoint) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        return new EventEnvelope<>(eventId, "OrderItemConfirmed", OffsetDateTime.now(), "x", 1,
                new OrderItemConfirmedPayload(orderItemId, ORDER_NO, userId, itemAmt, earnedPoint));
    }

    private User reload(Long userId) {
        return userRepository.findById(userId).orElseThrow();
    }

    private List<PointHistory> earnsOf(long orderItemId) {
        return pointHistoryRepository.findByOrderItemIdAndTypeIn(orderItemId, List.of(PointType.EARN));
    }

    @Test
    @DisplayName("구매확정 → 누적 금액 + 적립 (EARN 이력 ref_key earn-{orderItemId}), 상품주문마다 따로")
    void apply_accumulatesAndEarns() {
        Long userId = member();

        PurchaseConfirmedService.Result first = purchaseConfirmedService.apply(confirmed(userId, 11L, 20_000L, 900L));
        PurchaseConfirmedService.Result second = purchaseConfirmedService.apply(confirmed(userId, 12L, 8_000L, 400L));

        assertThat(first).isEqualTo(new PurchaseConfirmedService.Result(true, 900L));
        assertThat(second).isEqualTo(new PurchaseConfirmedService.Result(true, 400L));
        assertThat(reload(userId).getTotalConfirmedAmt()).isEqualTo(28_000L);
        assertThat(reload(userId).getPointBalance()).isEqualTo(1_300L);
        assertThat(earnsOf(11L)).singleElement().satisfies(h -> {
            assertThat(h.getAmount()).isEqualTo(900L);
            assertThat(h.getBalanceAfter()).isEqualTo(900L);
            assertThat(h.getRefKey()).isEqualTo("earn-11");
            assertThat(h.getOrderNo()).isEqualTo(ORDER_NO);
        });
    }

    @Test
    @DisplayName("같은 eventId 재전달은 아무것도 안 함, 다른 eventId 로 다시 와도 적립은 상품주문당 한 번")
    void apply_idempotent() {
        Long userId = member();
        EventEnvelope<OrderItemConfirmedPayload> event = confirmed(userId, 21L, 10_000L, 500L);

        purchaseConfirmedService.apply(event);
        PurchaseConfirmedService.Result retry = purchaseConfirmedService.apply(event);
        PurchaseConfirmedService.Result republished = purchaseConfirmedService.apply(confirmed(userId, 21L, 10_000L, 500L));

        assertThat(retry).isEqualTo(new PurchaseConfirmedService.Result(false, 0L));
        assertThat(republished.earned()).isZero();
        assertThat(reload(userId).getPointBalance()).isEqualTo(500L);
        assertThat(earnsOf(21L)).hasSize(1);
    }

    @Test
    @DisplayName("적립 포인트 0 (전액 포인트 결제 등) — 누적만, EARN 이력 없음")
    void apply_zeroPoint_accumulatesOnly() {
        Long userId = member();

        PurchaseConfirmedService.Result result = purchaseConfirmedService.apply(confirmed(userId, 31L, 5_000L, 0L));

        assertThat(result).isEqualTo(new PurchaseConfirmedService.Result(true, 0L));
        assertThat(reload(userId).getTotalConfirmedAmt()).isEqualTo(5_000L);
        assertThat(earnsOf(31L)).isEmpty();
    }

    @Test
    @DisplayName("탈퇴 회원 · 없는 회원 — 누적 · 적립 없이 처리됨으로 남김")
    void apply_withdrawnOrUnknown_marksProcessed() {
        Long userId = member();
        transactionTemplate.executeWithoutResult(status -> reload(userId).softDelete(LocalDateTime.now()));
        EventEnvelope<OrderItemConfirmedPayload> withdrawn = confirmed(userId, 41L, 5_000L, 250L);
        EventEnvelope<OrderItemConfirmedPayload> unknown = confirmed(Long.MAX_VALUE, 42L, 5_000L, 250L);

        assertThat(purchaseConfirmedService.apply(withdrawn).applied()).isFalse();
        assertThat(purchaseConfirmedService.apply(unknown).applied()).isFalse();

        assertThat(reload(userId).getPointBalance()).isZero();
        assertThat(reload(userId).getTotalConfirmedAmt()).isZero();
        assertThat(earnsOf(41L)).isEmpty();
        assertThat(processedEventRepository.existsByEventId(withdrawn.eventId())).isTrue();
        assertThat(processedEventRepository.existsByEventId(unknown.eventId())).isTrue();
    }

    @Test
    @DisplayName("같은 이벤트를 동시에 5번 받아도 누적 · 적립은 한 번 (회원 행 잠금 · processed_event)")
    void apply_concurrentSameEvent_once() throws Exception {
        Long userId = member();
        EventEnvelope<OrderItemConfirmedPayload> event = confirmed(userId, 51L, 10_000L, 500L);
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<PurchaseConfirmedService.Result>> futures = new ArrayList<>();
        try {
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return purchaseConfirmedService.apply(event);
                }));
            }
            start.countDown();
            long applied = 0;
            for (Future<PurchaseConfirmedService.Result> f : futures) {
                if (f.get().applied()) {
                    applied++;
                }
            }
            assertThat(applied).isEqualTo(1);
        } finally {
            pool.shutdown();
        }

        assertThat(reload(userId).getPointBalance()).isEqualTo(500L);
        assertThat(reload(userId).getTotalConfirmedAmt()).isEqualTo(10_000L);
        assertThat(earnsOf(51L)).hasSize(1);
    }
}
