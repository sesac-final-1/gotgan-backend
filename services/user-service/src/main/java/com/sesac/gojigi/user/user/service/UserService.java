package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.user.client.StoreClient;
import com.sesac.gojigi.user.user.entity.User;
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
 * 회원 — 탈퇴.
 * 탈퇴 = (판매자면 스토어 폐점 확인) → ① users 소프트 삭제(deleted_at, 이메일은 그대로 → 같은 이메일 재가입 불가)
 *        → ② Keycloak 계정 삭제(새 로그인 · Refresh 불가) → ③ Redis 차단 목록(남은 Access Token 을 gateway 가 401).
 * ① 이 끝나면 탈퇴는 확정 — ② · ③ 은 실패해도 되돌리지 않고 warn 만 남긴다 (되돌릴 수 없고, 1차 방어선이 남아 있음):
 * - ② 실패: 남은 Keycloak 계정으로 로그인해도 토큰의 user_id 가 탈퇴 회원이라 AUTH_ACCOUNT_NOT_LINKED
 * - ③ 실패: 남은 Access Token 은 만료(최대 TTL)까지 다른 서비스 호출 가능 — user-service 는 deleted_at 으로 막는다
 * 외부 호출(store · Keycloak · Redis) 동안 DB 커넥션을 잡지 않도록 트랜잭션은 짧게 (TransactionTemplate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final AuthService authService;
    private final StoreClient storeClient;
    private final BlockedUserRedisRepository blockedUserRedisRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /**
     * @param storeId 게이트웨이가 넣어 준 X-Store-Id — 판매자만 있다 (토큰 store_id 클레임)
     */
    public void withdraw(Long userId, Long storeId) {
        if (storeId != null && !storeClient.getStoreStatus(storeId).isClosed()) {
            throw new UserException(UserErrorCode.USER_SELLER_STORE_NOT_CLOSED);   // 판매자는 폐점 후 탈퇴
        }
        User user = transactionTemplate.execute(status -> softDelete(userId));
        boolean accountDeleted = deleteKeycloakAccount(user);
        boolean tokensBlocked = blockRemainingTokens(userId);
        log.info("회원 탈퇴 userId={}, keycloakAccountDeleted={}, remainingTokensBlocked={}",
                userId, accountDeleted, tokensBlocked);
    }

    private User softDelete(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));   // 없는 회원 · 이미 탈퇴
        // TODO 진행 중인 주문 · 교환 · 반품이 있으면 탈퇴 불가 — order-service 내부 API 가 생기면 여기서 확인
        user.softDelete(LocalDateTime.now(clock));
        return user;   // 트랜잭션 커밋 때 변경 감지로 UPDATE
    }

    private boolean deleteKeycloakAccount(User user) {
        try {
            authService.deleteAccount(user.getKeycloakId(), user.getEmail());
            return true;
        } catch (BusinessException e) {
            log.warn("탈퇴 후 Keycloak 계정 삭제 실패 — 탈퇴는 유지. 남은 계정으로 로그인하면 AUTH_ACCOUNT_NOT_LINKED 로 막힘, "
                    + "Keycloak 관리 콘솔에서 계정 수동 삭제 필요 userId={}, keycloakId={}, code={}, cause={}",
                    user.getId(), user.getKeycloakId(), e.getErrorCode().getCode(), String.valueOf(e.getCause()));
            return false;
        }
    }

    private boolean blockRemainingTokens(Long userId) {
        try {
            blockedUserRedisRepository.block(userId, BlockedUserRedisRepository.WITHDRAWN);
            return true;
        } catch (RuntimeException e) {
            log.warn("탈퇴 후 차단 목록 저장 실패 — 탈퇴는 유지. 남은 Access Token 은 만료(최대 {}분)까지 다른 서비스 호출 가능, "
                    + "user-service 는 deleted_at 으로 차단 userId={}, key=blocked:user:{}, cause={}",
                    blockedUserRedisRepository.ttl().toMinutes(), userId, userId, e.toString());
            return false;
        }
    }
}
