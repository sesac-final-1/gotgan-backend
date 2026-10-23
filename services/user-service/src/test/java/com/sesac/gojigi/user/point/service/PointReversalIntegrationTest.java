package com.sesac.gojigi.user.point.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.point.dto.request.ReversePointRequest;
import com.sesac.gojigi.user.point.dto.response.PointReversalResponse;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.point.repository.PointUseSagaRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 취소 · 반품 포인트 환원 · 회수 — 실제 MySQL. 적립된 만큼만 회수 · 잔액 0 까지만 · 클레임 멱등 · 동시 요청 한 번만 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, PointService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PointReversalIntegrationTest {

    private static final String ORDER_NO = "20261023-000001";
    private static final long ORDER_ITEM_ID = 456L;

    @Autowired private PointService pointService;
    @Autowired private UserRepository userRepository;
    @Autowired private PointHistoryRepository pointHistoryRepository;
    @Autowired private PointUseSagaRepository pointUseSagaRepository;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanUp() {
        pointHistoryRepository.deleteAllInBatch();
        pointUseSagaRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    /** 잔액 balance 회원 — 이 상품주문으로 earned 만큼 적립돼 있던 것으로 (0 이면 적립 전) */
    private Long member(long balance, long earned) {
        User user = userRepository.save(User.signUp("rv-" + System.nanoTime() + "@gotgan.com", "환원", null, null));
        transactionTemplate.executeWithoutResult(status -> {
            userRepository.addPoint(user.getId(), balance);
            if (earned > 0) {
                pointHistoryRepository.save(PointHistory.earn(user, ORDER_ITEM_ID, ORDER_NO, earned, balance, "evt-1-" + ORDER_ITEM_ID));
            }
        });
        return user.getId();
    }

    private static ReversePointRequest request(Long userId, long restore, long reclaim) {
        return new ReversePointRequest(789L, ORDER_ITEM_ID, userId, ORDER_NO, restore, reclaim);
    }

    private long balanceOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPointBalance();
    }

    @Test
    @DisplayName("환원 후 회수 — 쓴 포인트를 돌려주고 적립된 포인트를 빼며, 같은 클레임 재요청은 처음 결과 그대로")
    void reverse_restoreThenReclaim_once() {
        // given — 잔액 1,000 · 이 상품주문으로 300 적립됨
        Long userId = member(1_000L, 300L);

        // when
        PointReversalResponse first = pointService.reverse(request(userId, 2_000L, 300L));
        PointReversalResponse retry = pointService.reverse(request(userId, 2_000L, 300L));

        // then — 1,000 + 2,000 - 300
        assertThat(first).isEqualTo(new PointReversalResponse(789L, ORDER_ITEM_ID, 2_000L, 300L, 0L, 2_700L));
        assertThat(retry).isEqualTo(first);
        assertThat(balanceOf(userId)).isEqualTo(2_700L);
        assertThat(pointHistoryRepository.findByOrderItemIdAndTypeIn(ORDER_ITEM_ID, List.of(PointType.RESTORE, PointType.RECLAIM)))
                .extracting(PointHistory::getType, PointHistory::getRefKey)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple(PointType.RESTORE, "claim-789-456"),
                        org.assertj.core.groups.Tuple.tuple(PointType.RECLAIM, "claim-789-456"));
    }

    @Test
    @DisplayName("적립 전(EARN 없음) — 회수할 게 없어 환원만")
    void reverse_notEarnedYet_restoreOnly() {
        Long userId = member(0L, 0L);

        PointReversalResponse result = pointService.reverse(request(userId, 1_500L, 400L));

        assertThat(result.restoredAmount()).isEqualTo(1_500L);
        assertThat(result.reclaimedAmount()).isZero();
        assertThat(balanceOf(userId)).isEqualTo(1_500L);
    }

    @Test
    @DisplayName("잔액이 모자라면 0 까지만 회수하고 못 뺀 차액을 남긴다 · 요청보다 적게 적립됐으면 적립분까지만")
    void reverse_shortfall() {
        // given — 잔액 100 · 적립 250 (요청은 400)
        Long userId = member(100L, 250L);

        // when
        PointReversalResponse result = pointService.reverse(request(userId, 0L, 400L));

        // then
        assertThat(result).isEqualTo(new PointReversalResponse(789L, ORDER_ITEM_ID, 0L, 100L, 150L, 0L));
        assertThat(balanceOf(userId)).isZero();
    }

    @Test
    @DisplayName("한 일이 없으면(둘 다 0 · 잔액 0 이라 회수 못 함 · 탈퇴 회원) 모두 0")
    void reverse_nothingToDo() {
        Long zero = member(0L, 0L);
        Long broke = member(0L, 200L);
        Long withdrawn = member(500L, 0L);
        jdbc.update("update users set deleted_at = ? where user_id = ?", LocalDateTime.now(), withdrawn);

        assertThat(pointService.reverse(request(zero, 0L, 0L)))
                .isEqualTo(new PointReversalResponse(789L, ORDER_ITEM_ID, 0L, 0L, 0L, null));
        assertThat(pointService.reverse(request(broke, 0L, 200L)).reclaimedAmount()).isZero();
        assertThat(pointService.reverse(request(withdrawn, 1_000L, 0L)).restoredAmount()).isZero();
        assertThat(balanceOf(withdrawn)).isEqualTo(500L);
    }

    @Test
    @DisplayName("같은 클레임이 동시에 두 번 와도 한 번만 환원")
    void reverse_concurrent_once() throws Exception {
        // given
        Long userId = member(0L, 0L);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        // when
        Future<PointReversalResponse> a = pool.submit(() -> {
            start.await();
            return pointService.reverse(request(userId, 1_000L, 0L));
        });
        Future<PointReversalResponse> b = pool.submit(() -> {
            start.await();
            return pointService.reverse(request(userId, 1_000L, 0L));
        });
        start.countDown();

        // then
        assertThat(a.get().restoredAmount()).isEqualTo(1_000L);
        assertThat(b.get().restoredAmount()).isEqualTo(1_000L);
        assertThat(balanceOf(userId)).isEqualTo(1_000L);
        pool.shutdown();
    }
}
