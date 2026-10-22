package com.sesac.gojigi.user.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.user.dto.request.ChangeUserStatusRequest;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.repository.BlockedUserRedisRepository;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/** 이용정지 · 해제 후속 처리 순서와 실패 시 정책 (DB 동작은 UserStatusServiceIntegrationTest) */
@ExtendWith(MockitoExtension.class)
class UserStatusServiceTest {

    /** 2026-10-22 12:00 KST */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-22T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 22, 12, 0);

    @Mock
    private UserRepository userRepository;
    @Mock
    private AuthService authService;
    @Mock
    private BlockedUserRedisRepository blockedUserRedisRepository;
    @Mock
    private TransactionTemplate transactionTemplate;

    private UserStatusService userStatusService;

    @BeforeEach
    void setUp() {
        userStatusService = new UserStatusService(userRepository, authService, blockedUserRedisRepository,
                transactionTemplate, CLOCK);
        // 트랜잭션 템플릿은 콜백을 그대로 실행
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation ->
                invocation.<TransactionCallback<?>>getArgument(0).doInTransaction(new SimpleTransactionStatus()));
        lenient().when(blockedUserRedisRepository.ttl()).thenReturn(Duration.ofMinutes(30));
    }

    private static User member(long id) {
        User user = User.signUp("b@gotgan.com", "회원", null, "010-1234-5678");
        ReflectionTestUtils.setField(user, "id", id);
        user.linkKeycloak("kc-" + id);
        return user;
    }

    private static User suspendedMember(long id, LocalDateTime until) {
        User user = member(id);
        user.suspend("허위 리뷰", NOW.minusDays(7), until);
        return user;
    }

    @Test
    @DisplayName("7일 정지 — users SUSPENDED(종료 시각 = 지금 + 7일) → Keycloak 비활성 · 세션 종료 → 차단 목록 순서")
    void suspend_withDays_suspendsThenDisablesAccountThenBlocks() {
        User user = member(7L);
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));

        AdminUserDetailResponse response = userStatusService.changeStatus(7L,
                new ChangeUserStatusRequest(UserStatus.SUSPENDED, "  허위 리뷰  ", 7), 1L);

        assertThat(user.getStatus()).isEqualTo(UserStatus.SUSPENDED);
        assertThat(user.getStatusReason()).isEqualTo("허위 리뷰");
        assertThat(user.getSuspendedAt()).isEqualTo(NOW);
        assertThat(user.getSuspendedUntil()).isEqualTo(NOW.plusDays(7));
        assertThat(response.suspendedUntil()).hasToString("2026-10-29T12:00+09:00");
        InOrder order = inOrder(userRepository, authService, blockedUserRedisRepository);
        order.verify(userRepository).findByIdForUpdate(7L);
        order.verify(authService).suspendAccount("kc-7", "b@gotgan.com");
        order.verify(blockedUserRedisRepository).block(7L, BlockedUserRedisRepository.SUSPENDED);
    }

    @Test
    @DisplayName("days 생략 — 영구 정지 (종료 시각 없음)")
    void suspend_withoutDays_isPermanent() {
        User user = member(7L);
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));

        userStatusService.changeStatus(7L, new ChangeUserStatusRequest(UserStatus.SUSPENDED, "결제 도용", null), null);

        assertThat(user.getSuspendedUntil()).isNull();
        assertThat(user.isSuspensionExpired(NOW.plusYears(10))).isFalse();
    }

    @Test
    @DisplayName("정지 — Keycloak · Redis 가 실패해도 정지는 유지되고 나머지 단계는 계속한다")
    void suspend_whenKeycloakAndRedisFail_keepsSuspension() {
        User user = member(7L);
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));
        willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR)).given(authService).suspendAccount("kc-7", "b@gotgan.com");
        willThrow(new RedisConnectionFailureException("down")).given(blockedUserRedisRepository).block(7L, BlockedUserRedisRepository.SUSPENDED);

        AdminUserDetailResponse response = userStatusService.changeStatus(7L,
                new ChangeUserStatusRequest(UserStatus.SUSPENDED, "허위 리뷰", 30), 1L);

        assertThat(response.status()).isEqualTo(UserStatus.SUSPENDED);
        verify(blockedUserRedisRepository).block(7L, BlockedUserRedisRepository.SUSPENDED);
    }

    @Test
    @DisplayName("정지 — 사유 없음 · 이미 정지 · 없는 회원은 Keycloak · Redis 를 건드리지 않는다")
    void suspend_invalid_doesNothingElse() {
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(member(7L)));
        given(userRepository.findByIdForUpdate(8L)).willReturn(Optional.of(suspendedMember(8L, null)));
        given(userRepository.findByIdForUpdate(9L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> userStatusService.changeStatus(7L,
                new ChangeUserStatusRequest(UserStatus.SUSPENDED, "   ", 7), 1L))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_STATUS_REASON_REQUIRED);
        assertThatThrownBy(() -> userStatusService.changeStatus(8L,
                new ChangeUserStatusRequest(UserStatus.SUSPENDED, "재정지", 7), 1L))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_INVALID_STATUS_TRANSITION);
        assertThatThrownBy(() -> userStatusService.changeStatus(9L,
                new ChangeUserStatusRequest(UserStatus.SUSPENDED, "사유", 7), 1L))
                .extracting("errorCode").isEqualTo(UserErrorCode.USER_NOT_FOUND);
        verifyNoInteractions(authService);
        verify(blockedUserRedisRepository, never()).block(any(), any());
    }

    @Test
    @DisplayName("해제 — users ACTIVE(정지 시각 · 종료 시각 지움) → Keycloak 활성 → 차단 목록 삭제, Keycloak 실패해도 해제 유지")
    void activate_restoresAccountAndUnblocks() {
        User user = suspendedMember(7L, NOW.plusDays(3));
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));
        willThrow(new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR)).given(authService).restoreAccount("kc-7", "b@gotgan.com");

        AdminUserDetailResponse response = userStatusService.changeStatus(7L,
                new ChangeUserStatusRequest(UserStatus.ACTIVE, "소명 완료", 30), 1L);

        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getSuspendedUntil()).isNull();
        assertThat(user.getStatusReason()).isEqualTo("소명 완료");
        InOrder order = inOrder(authService, blockedUserRedisRepository);
        order.verify(authService).restoreAccount("kc-7", "b@gotgan.com");
        order.verify(blockedUserRedisRepository).unblock(7L);
    }

    @Test
    @DisplayName("기간 만료 해제 — 종료 시각이 지났으면 자동 해제 사유로 해제 · Keycloak 활성 · 차단 목록 삭제")
    void expireSuspension_whenExpired_activates() {
        User user = suspendedMember(7L, NOW.minusMinutes(1));
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(user));

        boolean released = userStatusService.expireSuspension(7L);

        assertThat(released).isTrue();
        assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(user.getStatusReason()).isEqualTo(UserStatusService.EXPIRED_REASON);
        verify(authService).restoreAccount("kc-7", "b@gotgan.com");
        verify(blockedUserRedisRepository).unblock(7L);
    }

    @Test
    @DisplayName("기간 만료 해제 — 잠근 뒤 보니 기간이 남았거나 영구 · 이미 해제 · 없는 회원이면 건너뛴다")
    void expireSuspension_whenNotExpired_skips() {
        given(userRepository.findByIdForUpdate(7L)).willReturn(Optional.of(suspendedMember(7L, NOW.plusMinutes(1))));
        given(userRepository.findByIdForUpdate(8L)).willReturn(Optional.of(suspendedMember(8L, null)));
        given(userRepository.findByIdForUpdate(9L)).willReturn(Optional.of(member(9L)));
        given(userRepository.findByIdForUpdate(10L)).willReturn(Optional.empty());

        for (long id = 7L; id <= 10L; id++) {
            assertThat(userStatusService.expireSuspension(id)).isFalse();
        }
        verifyNoInteractions(authService);
        verify(blockedUserRedisRepository, never()).unblock(any());
    }
}
