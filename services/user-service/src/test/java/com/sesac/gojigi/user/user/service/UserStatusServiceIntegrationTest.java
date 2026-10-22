package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.user.TestcontainersConfiguration;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.common.config.ClockConfig;
import com.sesac.gojigi.user.user.dto.request.ChangeUserStatusRequest;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.BlockedUserRedisRepository;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.sesac.gojigi.user.user.scheduler.SuspensionExpiryScheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 실제 MySQL — 정지 종료 시각 저장(V3), 동시 정지에서 한 건만 성공하는지(회원 행 잠금), 기간 만료 자동 해제(스케줄러 쿼리 · 재확인).
 * Keycloak · Redis 는 목. 서비스가 TransactionTemplate 으로 직접 커밋해야 해서 테스트 트랜잭션으로 감싸지 않는다 (끝나고 직접 정리).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, ClockConfig.class, UserStatusService.class, SuspensionExpiryScheduler.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserStatusServiceIntegrationTest {

    @Autowired
    private UserStatusService userStatusService;
    @Autowired
    private SuspensionExpiryScheduler suspensionExpiryScheduler;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockitoBean
    private AuthService authService;
    @MockitoBean
    private BlockedUserRedisRepository blockedUserRedisRepository;

    private final List<Long> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        userRepository.deleteAllByIdInBatch(created);
    }

    private Long member() {
        Long id = userRepository.save(User.signUp("st-" + System.nanoTime() + "@gotgan.com", "회원", null, null)).getId();
        created.add(id);
        return id;
    }

    /** 이미 시작된 정지 — 종료 시각을 과거 · 미래로 직접 */
    private Long suspendedMember(LocalDateTime until) {
        Long id = member();
        transactionTemplate.executeWithoutResult(status ->
                userRepository.findById(id).orElseThrow().suspend("허위 리뷰", LocalDateTime.now().minusDays(7), until));
        return id;
    }

    private User reload(Long id) {
        return userRepository.findById(id).orElseThrow();
    }

    @Test
    @DisplayName("7일 정지 → suspended_until 저장, 해제하면 정지 시각 · 종료 시각 · 차단 목록 정리")
    void suspendThenActivate_persists() {
        Long id = member();

        userStatusService.changeStatus(id, new ChangeUserStatusRequest(UserStatus.SUSPENDED, "허위 리뷰", 7), 1L);
        User suspended = reload(id);

        assertThat(suspended.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(suspended.getSuspendedUntil()).isEqualTo(suspended.getSuspendedAt().plusDays(7));

        userStatusService.changeStatus(id, new ChangeUserStatusRequest(UserStatus.ACTIVE, "소명 완료", null), 1L);
        User active = reload(id);

        assertThat(active.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(active.getSuspendedAt()).isNull();
        assertThat(active.getSuspendedUntil()).isNull();
        verify(blockedUserRedisRepository).block(id, BlockedUserRedisRepository.SUSPENDED);
        verify(blockedUserRedisRepository).unblock(id);
    }

    @Test
    @DisplayName("같은 회원 동시 정지 5건 — 회원 행 잠금으로 1건만 성공, 나머지는 409 USER_INVALID_STATUS_TRANSITION")
    void concurrentSuspend_onlyOneSucceeds() throws Exception {
        Long id = member();
        int threads = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<String>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int days = i + 1;
            futures.add(pool.submit(() -> {
                start.await();
                try {
                    userStatusService.changeStatus(id, new ChangeUserStatusRequest(UserStatus.SUSPENDED, "사유", days), 1L);
                    return "OK";
                } catch (UserException e) {
                    return e.getErrorCode().getCode();
                }
            }));
        }
        start.countDown();
        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get());
        }
        pool.shutdown();

        assertThat(results).filteredOn("OK"::equals).hasSize(1);
        assertThat(results).filteredOn(UserErrorCode.USER_INVALID_STATUS_TRANSITION.getCode()::equals).hasSize(threads - 1);
        verify(authService, times(1)).suspendAccount(any(), anyString());   // keycloak_id 없는 회원 — username 으로 찾는다
    }

    @Test
    @DisplayName("스케줄러 — 종료 시각이 지난 기간 정지만 자동 해제, 기간이 남은 정지 · 영구 정지는 그대로")
    void scheduler_releasesOnlyExpired() {
        Long expired = suspendedMember(LocalDateTime.now().minusMinutes(1));
        Long remaining = suspendedMember(LocalDateTime.now().plusDays(3));
        Long permanent = suspendedMember(null);

        assertThat(userRepository.findSuspensionExpiredIds(UserStatus.SUSPENDED, LocalDateTime.now()))
                .contains(expired).doesNotContain(remaining, permanent);

        suspensionExpiryScheduler.releaseExpired();

        assertThat(reload(expired).getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(reload(expired).getStatusReason()).isEqualTo(UserStatusService.EXPIRED_REASON);
        assertThat(reload(remaining).getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(reload(permanent).getStatus()).isEqualTo(UserStatus.SUSPENDED);
        verify(blockedUserRedisRepository).unblock(expired);

        // 다시 돌아도 이미 해제된 회원은 건너뛴다
        suspensionExpiryScheduler.releaseExpired();
        verify(blockedUserRedisRepository, times(1)).unblock(expired);
    }
}
