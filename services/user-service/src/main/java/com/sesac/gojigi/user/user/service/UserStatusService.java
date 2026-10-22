package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.user.dto.request.ChangeUserStatusRequest;
import com.sesac.gojigi.user.user.dto.response.AdminUserDetailResponse;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.BlockedUserRedisRepository;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 관리자 이용정지 · 해제, 기간 만료 자동 해제 (USR-10).
 * 정지 = ① users SUSPENDED (회원 행 잠금 · 상태 전이 검사) → ② Keycloak 계정 비활성 + 모든 세션 종료(Refresh Token 전부 무효)
 *        → ③ Redis 차단 목록(남은 Access Token 을 gateway 가 401).
 * 해제 = ① users ACTIVE → ② Keycloak 계정 다시 활성 → ③ 차단 목록 삭제 (남겨 두면 해제 후 새 토큰도 TTL 동안 401).
 * ① 이 끝나면 확정 — ② · ③ 은 실패해도 되돌리지 않고 로그만 남긴다 (탈퇴와 같은 방식):
 * - 정지 ② 실패: user-service 로그인 · 재발급은 users.status 로 이미 막힌다. Keycloak 관리 콘솔에서 수동 비활성 필요
 * - 해제 ② 실패: Keycloak 이 로그인을 거절한다 — 관리 콘솔에서 수동 활성 필요 (사람이 봐야 해서 error)
 * 외부 호출(Keycloak · Redis) 동안 DB 커넥션을 잡지 않도록 트랜잭션은 짧게 (TransactionTemplate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserStatusService {
    static final String EXPIRED_REASON = "정지 기간 만료 (자동 해제)";

    private final UserRepository userRepository;
    private final AuthService authService;
    private final BlockedUserRedisRepository blockedUserRedisRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * @param adminId 처리한 관리자 (admin-service 가 넘긴 X-User-Id, 로그용 — 없을 수 있음)
     */
    public AdminUserDetailResponse changeStatus(Long userId, ChangeUserStatusRequest request, Long adminId) {
        return request.status() == UserStatus.SUSPENDED
                ? suspend(userId, request.trimmedReason(), request.days(), adminId)
                : activate(userId, request.trimmedReason(), adminId);
    }

    /**
     * 스케줄러 — 기간이 끝난 정지를 해제한다. 잠근 뒤 다시 확인해 이미 해제됐거나 기간이 남았으면 건너뛴다(false).
     */
    public boolean expireSuspension(Long userId) {
        LocalDateTime now = LocalDateTime.now(clock);
        User user = transactionTemplate.execute(status -> {
            User found = userRepository.findByIdForUpdate(userId).orElse(null);
            if (found == null || !found.isSuspensionExpired(now)) {
                return null;
            }
            found.activate(EXPIRED_REASON);
            return found;
        });
        if (user == null) {
            return false;
        }
        boolean accountRestored = restoreKeycloakAccount(user);
        boolean unblocked = unblock(userId);
        log.info("이용정지 기간 만료 자동 해제 userId={}, keycloakEnabled={}, blocklistRemoved={}",
                userId, accountRestored, unblocked);
        return true;
    }

    private AdminUserDetailResponse suspend(Long userId, String reason, Integer days, Long adminId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime until = days == null ? null : now.plusDays(days);
        User user = transactionTemplate.execute(status -> {
            User found = findForUpdate(userId);
            found.suspend(reason, now, until);
            return found;   // 트랜잭션 커밋 때 변경 감지로 UPDATE
        });
        boolean accountSuspended = suspendKeycloakAccount(user);
        boolean tokensBlocked = blockRemainingTokens(userId);
        log.info("회원 이용정지 userId={}, adminId={}, suspendedUntil={}, keycloakDisabled={}, remainingTokensBlocked={}",
                userId, adminId, until == null ? "영구" : until, accountSuspended, tokensBlocked);
        return AdminUserDetailResponse.from(user);
    }

    private AdminUserDetailResponse activate(Long userId, String reason, Long adminId) {
        User user = transactionTemplate.execute(status -> {
            User found = findForUpdate(userId);
            found.activate(reason);
            return found;
        });
        boolean accountRestored = restoreKeycloakAccount(user);
        boolean unblocked = unblock(userId);
        log.info("회원 이용정지 해제 userId={}, adminId={}, keycloakEnabled={}, blocklistRemoved={}",
                userId, adminId, accountRestored, unblocked);
        return AdminUserDetailResponse.from(user);
    }

    /** 같은 회원을 동시에 정지 · 해제하면 하나만 전이에 성공하고 나머지는 USER_INVALID_STATUS_TRANSITION */
    private User findForUpdate(Long userId) {
        return userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));   // 없는 회원 · 탈퇴
    }

    private boolean suspendKeycloakAccount(User user) {
        try {
            authService.suspendAccount(user.getKeycloakId(), user.getEmail());
            return true;
        } catch (BusinessException e) {
            log.error("이용정지 후 Keycloak 계정 비활성 · 세션 종료 실패 — 정지는 유지, user-service 로그인 · 재발급은 users.status 로 차단됨. "
                    + "Keycloak 관리 콘솔에서 계정 비활성(Enabled off) · 세션 로그아웃 필요 userId={}, keycloakId={}, code={}",
                    user.getId(), user.getKeycloakId(), e.getErrorCode().getCode(), e);
            return false;
        }
    }

    private boolean restoreKeycloakAccount(User user) {
        try {
            authService.restoreAccount(user.getKeycloakId(), user.getEmail());
            return true;
        } catch (BusinessException e) {
            log.error("정지 해제 후 Keycloak 계정 활성 실패 — 해제는 유지, Keycloak 이 로그인을 거절함. "
                    + "Keycloak 관리 콘솔에서 계정 활성(Enabled on) 필요 userId={}, keycloakId={}, code={}",
                    user.getId(), user.getKeycloakId(), e.getErrorCode().getCode(), e);
            return false;
        }
    }

    private boolean blockRemainingTokens(Long userId) {
        try {
            blockedUserRedisRepository.block(userId, BlockedUserRedisRepository.SUSPENDED);
            return true;
        } catch (RuntimeException e) {
            log.warn("이용정지 후 차단 목록 저장 실패 — 정지는 유지. 남은 Access Token 은 만료(최대 {}분)까지 다른 서비스 호출 가능 "
                    + "userId={}, key=blocked:user:{}, cause={}",
                    blockedUserRedisRepository.ttl().toMinutes(), userId, userId, e.toString());
            return false;
        }
    }

    private boolean unblock(Long userId) {
        try {
            blockedUserRedisRepository.unblock(userId);
            return true;
        } catch (RuntimeException e) {
            log.warn("정지 해제 후 차단 목록 삭제 실패 — 해제는 유지. 키가 만료(최대 {}분)될 때까지 새 토큰도 gateway 에서 401 "
                    + "userId={}, key=blocked:user:{}, cause={}",
                    blockedUserRedisRepository.ttl().toMinutes(), userId, userId, e.toString());
            return false;
        }
    }
}
