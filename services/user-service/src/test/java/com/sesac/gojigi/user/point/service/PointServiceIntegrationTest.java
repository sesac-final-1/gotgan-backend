package com.sesac.gojigi.user.point.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.point.dto.request.UsePointRequest;
import com.sesac.gojigi.user.point.dto.response.MyPointsResponse;
import com.sesac.gojigi.user.point.dto.response.PointRestoreResponse;
import com.sesac.gojigi.user.point.dto.response.UserPointsResponse;
import com.sesac.gojigi.user.point.entity.PointHistory;
import com.sesac.gojigi.user.point.entity.PointType;
import com.sesac.gojigi.user.point.exception.PointErrorCode;
import com.sesac.gojigi.user.point.repository.PointHistoryRepository;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
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
 * 실제 MySQL — 조건부 UPDATE 로 잔액이 맞게 바뀌는지, sagaId 멱등 · 동시 요청에서 한 번만 차감되는지.
 * 서비스가 TransactionTemplate 으로 직접 커밋해야 해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, PointService.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PointServiceIntegrationTest {

    @Autowired
    private PointService pointService;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private PointHistoryRepository pointHistoryRepository;

    @AfterEach
    void cleanUp() {
        pointHistoryRepository.deleteAllInBatch();
        userRepository.deleteAllInBatch();
    }

    @Autowired
    private TransactionTemplate transactionTemplate;

    /** 잔액 1,000P 회원 — 조건부 UPDATE(@Modifying)는 트랜잭션 안에서만 */
    private Long memberWith1000() {
        User user = userRepository.save(User.signUp("pt-" + System.nanoTime() + "@gotgan.com", "포인트", null, null));
        transactionTemplate.executeWithoutResult(status -> userRepository.addPoint(user.getId(), 1_000L));
        return user.getId();
    }

    private long balanceOf(Long userId) {
        return userRepository.findById(userId).orElseThrow().getPointBalance();
    }

    @Test
    @DisplayName("사용 → 잔액 차감 · USE 이력, 같은 sagaId 재요청은 차감 없이 같은 결과")
    void use_thenRetry_deductsOnce() {
        Long userId = memberWith1000();
        UsePointRequest request = new UsePointRequest("saga-1", userId, "20261022-0001", 300L);

        PointService.UseResult first = pointService.use(request);
        PointService.UseResult retry = pointService.use(request);

        assertThat(first.created()).isTrue();
        assertThat(first.response().balanceAfter()).isEqualTo(700L);
        assertThat(retry.created()).isFalse();
        assertThat(retry.response()).isEqualTo(first.response());
        assertThat(balanceOf(userId)).isEqualTo(700L);
        assertThat(pointHistoryRepository.findAllByUserIdLatestFirst(userId)).hasSize(1);
    }

    @Test
    @DisplayName("잔액 부족 — 409 POINT_INSUFFICIENT, 잔액 · 이력 그대로")
    void use_insufficient_keepsBalance() {
        Long userId = memberWith1000();

        assertThatThrownBy(() -> pointService.use(new UsePointRequest("saga-2", userId, "20261022-0002", 1_001L)))
                .extracting("errorCode").isEqualTo(PointErrorCode.POINT_INSUFFICIENT);
        assertThat(balanceOf(userId)).isEqualTo(1_000L);
        assertThat(pointHistoryRepository.existsByTypeAndRefKey(PointType.USE, "saga-2")).isFalse();
    }

    @Test
    @DisplayName("같은 sagaId 동시 10건 — 한 번만 차감되고 모두 같은 결과 (유니크 제약에 걸린 쪽은 롤백 후 먼저 커밋된 결과)")
    void use_concurrentSameSaga_deductsOnce() throws Exception {
        Long userId = memberWith1000();
        UsePointRequest request = new UsePointRequest("saga-3", userId, "20261022-0003", 100L);
        int threads = 10;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<PointService.UseResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit((Callable<PointService.UseResult>) () -> {
                start.await();
                return pointService.use(request);
            }));
        }
        start.countDown();
        List<PointService.UseResult> results = new ArrayList<>();
        for (Future<PointService.UseResult> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn(PointService.UseResult::created).hasSize(1);
        assertThat(results).extracting(r -> r.response().balanceAfter()).containsOnly(900L);
        assertThat(balanceOf(userId)).isEqualTo(900L);
    }

    @Test
    @DisplayName("다른 sagaId 동시 사용 — 잔액보다 많이는 못 쓴다 (조건부 UPDATE, 음수 없음)")
    void use_concurrentDifferentSagas_neverNegative() throws Exception {
        Long userId = memberWith1000();
        int threads = 8;   // 300P × 8 = 2,400P 요청 · 잔액 1,000P → 3건만 성공
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Boolean>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String sagaId = "saga-c-" + i;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    pointService.use(new UsePointRequest(sagaId, userId, "20261022-0004", 300L));
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            }));
        }
        start.countDown();
        int success = 0;
        for (Future<Boolean> f : futures) {
            success += f.get() ? 1 : 0;
        }
        pool.shutdown();

        assertThat(success).isEqualTo(3);
        assertThat(balanceOf(userId)).isEqualTo(100L);
    }

    @Test
    @DisplayName("사용 취소 — 쓴 만큼 돌려주고 RESTORE 이력, 두 번 불러도 한 번만. 사용 기록 없는 sagaId 는 0")
    void cancelUse_restoresOnce() {
        Long userId = memberWith1000();
        pointService.use(new UsePointRequest("saga-5", userId, "20261022-0005", 400L));

        PointRestoreResponse first = pointService.cancelUse("saga-5");
        PointRestoreResponse again = pointService.cancelUse("saga-5");
        PointRestoreResponse none = pointService.cancelUse("saga-never-used");

        assertThat(first.restoredAmount()).isEqualTo(400L);
        assertThat(first.balanceAfter()).isEqualTo(1_000L);
        assertThat(again).isEqualTo(first);
        assertThat(none.restoredAmount()).isZero();
        assertThat(none.balanceAfter()).isNull();
        assertThat(balanceOf(userId)).isEqualTo(1_000L);
    }

    @Test
    @DisplayName("내 포인트 — 잔액 + 이력 최신순, 다른 회원 이력은 섞이지 않음")
    void getMyPoints_latestFirst() {
        Long me = memberWith1000();
        Long other = memberWith1000();
        pointService.use(new UsePointRequest("saga-6", me, "20261022-0006", 100L));
        pointService.use(new UsePointRequest("saga-7", me, "20261022-0007", 200L));
        pointService.use(new UsePointRequest("saga-8", other, "20261022-0008", 50L));
        pointService.cancelUse("saga-6");

        MyPointsResponse mine = pointService.getMyPoints(me);

        assertThat(mine.balance()).isEqualTo(800L);
        assertThat(mine.history()).extracting(MyPointsResponse.Item::type)
                .containsExactly(PointType.RESTORE, PointType.USE, PointType.USE);
        assertThat(mine.history()).extracting(MyPointsResponse.Item::orderNo)
                .containsExactly("20261022-0006", "20261022-0007", "20261022-0006");
        assertThat(mine.history().getFirst().createdAt()).isNotNull();
        assertThat(pointHistoryRepository.findAll()).extracting(PointHistory::getRefKey).contains("saga-8");
    }

    @Test
    @DisplayName("관리자 회원 포인트 — 잔액 + 이력 최신순 서버 페이징, 다른 회원 이력 제외")
    void getUserPoints_pagesLatestFirst() {
        Long member = memberWith1000();
        Long other = memberWith1000();
        pointService.use(new UsePointRequest("saga-9", member, "20261022-0009", 100L));
        pointService.use(new UsePointRequest("saga-10", member, "20261022-0010", 200L));
        pointService.use(new UsePointRequest("saga-11", other, "20261022-0011", 50L));
        pointService.cancelUse("saga-9");

        UserPointsResponse first = pointService.getUserPoints(member, 0, 2);
        UserPointsResponse second = pointService.getUserPoints(member, 1, 2);

        assertThat(first.balance()).isEqualTo(800L);
        assertThat(first.history().content()).extracting(MyPointsResponse.Item::type)
                .containsExactly(PointType.RESTORE, PointType.USE);
        assertThat(first.history().totalElements()).isEqualTo(3);
        assertThat(first.history().totalPages()).isEqualTo(2);
        assertThat(first.history().hasNext()).isTrue();
        assertThat(second.history().content()).extracting(MyPointsResponse.Item::orderNo).containsExactly("20261022-0009");
        assertThat(second.history().hasNext()).isFalse();
    }

    @Test
    @DisplayName("관리자 회원 포인트 — 이력이 없으면 잔액 + 빈 페이지, 탈퇴 · 없는 회원은 USER_NOT_FOUND")
    void getUserPoints_emptyAndNotFound() {
        Long member = memberWith1000();

        UserPointsResponse empty = pointService.getUserPoints(member, 0, 20);

        assertThat(empty.balance()).isEqualTo(1_000L);
        assertThat(empty.history().content()).isEmpty();
        assertThat(empty.history().totalElements()).isZero();

        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(member).orElseThrow().softDelete(LocalDateTime.now()));
        for (Long id : new Long[] { member, Long.MAX_VALUE }) {
            assertThatThrownBy(() -> pointService.getUserPoints(id, 0, 20))
                    .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        }
    }
}
