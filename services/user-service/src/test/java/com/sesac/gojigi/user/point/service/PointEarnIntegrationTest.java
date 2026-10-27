package com.sesac.gojigi.user.point.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.common.event.EventEnvelope;
import com.sesac.gojigi.user.common.event.ProcessedEventRepository;
import com.sesac.gojigi.user.point.dto.event.OrderPaidPayload;
import com.sesac.gojigi.user.point.dto.request.ReversePointRequest;
import com.sesac.gojigi.user.point.dto.response.PointReversalResponse;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.repository.PointEarnBlockRepository;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.point.repository.PointUseSagaRepository;
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
 * 결제 적립 (OrderPaid) — 실제 MySQL. 상품주문별 적립 · eventId · 상품주문 멱등 · 취소가 먼저 온 상품주문 건너뜀 · 취소와 동시에 와도 잔액이 맞는지.
 * 서비스가 TransactionTemplate 으로 직접 커밋해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, PointService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PointEarnIntegrationTest {

    private static final String ORDER_NO = "20261027-000001";

    @Autowired private PointService pointService;
    @Autowired private UserRepository userRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private PointUseSagaRepository pointUseSagaRepository;
    @Autowired private PointEarnBlockRepository pointEarnBlockRepository;
    @Autowired private ProcessedEventRepository processedEventRepository;
    @Autowired private TransactionTemplate transactionTemplate;

    private final List<String> eventIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        pointHistoryRepository.deleteAllInBatch();
        pointUseSagaRepository.deleteAllInBatch();
        pointEarnBlockRepository.deleteAllInBatch();
        processedEventRepository.findAll().stream()
                .filter(e -> eventIds.contains(e.getEventId()))
                .forEach(processedEventRepository::delete);
        userRepository.deleteAllInBatch();
    }

    private Long member() {
        return userRepository.save(User.signUp("earn-" + System.nanoTime() + "@gotgan.com", "적립", null, null)).getId();
    }

    private EventEnvelope<OrderPaidPayload> paid(Long userId, OrderPaidPayload.Item... items) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        return new EventEnvelope<>(eventId, "OrderPaid", OffsetDateTime.now(), ORDER_NO, 1,
                new OrderPaidPayload(ORDER_NO, userId, List.of(items)));
    }

    private static OrderPaidPayload.Item item(long orderItemId, long earnedPoint) {
        return new OrderPaidPayload.Item(orderItemId, earnedPoint);
    }

    private long balanceOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPointBalance();
    }

    private List<PointHistory> earnsOf(long orderItemId) {
        return pointHistoryRepository.findByOrderItemIdAndTypeIn(orderItemId, List.of(PointType.EARN));
    }

    @Test
    @DisplayName("상품주문별 earnedPoint 적립 — 이력 ref_key earn-{orderItemId}, 0P 상품주문은 이력 없음, processed_event 저장")
    void earn_perItem() {
        Long userId = member();
        EventEnvelope<OrderPaidPayload> event = paid(userId, item(101L, 300L), item(102L, 200L), item(103L, 0L));

        PointService.EarnResult result = pointService.earn(event);

        assertThat(result).isEqualTo(new PointService.EarnResult(500L, 0, false));
        assertThat(balanceOf(userId)).isEqualTo(500L);
        assertThat(earnsOf(101L)).singleElement().satisfies(h -> {
            assertThat(h.getAmount()).isEqualTo(300L);
            assertThat(h.getRefKey()).isEqualTo("earn-101");
            assertThat(h.getOrderNo()).isEqualTo(ORDER_NO);
        });
        assertThat(earnsOf(103L)).isEmpty();
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
    }

    @Test
    @DisplayName("같은 eventId 재전달은 DUPLICATE, 다른 eventId 로 다시 발행돼도 상품주문 기준이라 한 번만 적립")
    void earn_idempotent() {
        Long userId = member();
        EventEnvelope<OrderPaidPayload> event = paid(userId, item(201L, 300L));

        pointService.earn(event);
        PointService.EarnResult retry = pointService.earn(event);
        PointService.EarnResult republished = pointService.earn(paid(userId, item(201L, 300L)));

        assertThat(retry.duplicate()).isTrue();
        assertThat(republished).isEqualTo(new PointService.EarnResult(0L, 0, false));
        assertThat(balanceOf(userId)).isEqualTo(300L);
        assertThat(earnsOf(201L)).hasSize(1);
    }

    @Test
    @DisplayName("취소가 먼저 온 상품주문은 적립 건너뜀 — 나머지 상품주문만 적립 (취소 뒤 늦게 도착한 OrderPaid)")
    void earn_afterCancel_skipsCancelledItem() {
        Long userId = member();
        pointService.reverse(new ReversePointRequest(1L, 301L, userId, ORDER_NO, 0L, 300L));   // 적립 전 취소 — 회수할 게 없음

        PointService.EarnResult result = pointService.earn(paid(userId, item(301L, 300L), item(302L, 100L)));

        assertThat(result).isEqualTo(new PointService.EarnResult(100L, 1, false));
        assertThat(balanceOf(userId)).isEqualTo(100L);
        assertThat(earnsOf(301L)).isEmpty();
        assertThat(pointEarnBlockRepository.existsByOrderItemId(301L)).isTrue();
    }

    @Test
    @DisplayName("적립 뒤 취소 — 적립한 만큼 회수, 같은 OrderPaid 가 다시 와도 다시 적립하지 않음")
    void earn_thenCancel_reclaims() {
        Long userId = member();
        EventEnvelope<OrderPaidPayload> event = paid(userId, item(401L, 300L));
        pointService.earn(event);

        PointReversalResponse reversed = pointService.reverse(new ReversePointRequest(2L, 401L, userId, ORDER_NO, 0L, 300L));
        pointService.earn(paid(userId, item(401L, 300L)));

        assertThat(reversed.reclaimedAmount()).isEqualTo(300L);
        assertThat(balanceOf(userId)).isZero();
        assertThat(earnsOf(401L)).hasSize(1);
    }

    @Test
    @DisplayName("탈퇴 회원 — 적립하지 않고 처리됨으로 남김 (재전달돼도 같은 결과)")
    void earn_withdrawnUser_marksProcessed() {
        Long userId = member();
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(userId).orElseThrow().softDelete(LocalDateTime.now()));
        EventEnvelope<OrderPaidPayload> event = paid(userId, item(501L, 300L));

        PointService.EarnResult result = pointService.earn(event);

        assertThat(result).isEqualTo(new PointService.EarnResult(0L, 0, false));
        assertThat(earnsOf(501L)).isEmpty();
        assertThat(processedEventRepository.existsByEventId(event.eventId())).isTrue();
    }

    @Test
    @DisplayName("같은 상품주문의 적립 · 취소가 동시에 10회 — 어느 순서든 잔액 0, 적립이 남으면 반드시 회수도 남음")
    void concurrentEarnAndCancel_neverKeepsCancelledPoints() throws Exception {
        for (int round = 0; round < 10; round++) {
            Long userId = member();
            long orderItemId = 600L + round;
            EventEnvelope<OrderPaidPayload> event = paid(userId, item(orderItemId, 300L));
            ReversePointRequest cancel = new ReversePointRequest(10L + round, orderItemId, userId, ORDER_NO, 0L, 300L);
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                Future<?> earn = pool.submit(() -> {
                    start.await();
                    return pointService.earn(event);
                });
                Future<?> reverse = pool.submit(() -> {
                    start.await();
                    return pointService.reverse(cancel);
                });
                start.countDown();
                earn.get();
                reverse.get();
            } finally {
                pool.shutdown();
            }

            assertThat(balanceOf(userId)).as("round %d", round).isZero();
            int earned = earnsOf(orderItemId).size();
            int reclaimed = pointHistoryRepository.findByOrderItemIdAndTypeIn(orderItemId, List.of(PointType.RECLAIM)).size();
            assertThat(earned).as("round %d", round).isEqualTo(reclaimed);
        }
    }
}
