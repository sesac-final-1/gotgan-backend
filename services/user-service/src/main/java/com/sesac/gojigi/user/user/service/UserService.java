package com.sesac.gojigi.user.user.service;

import com.sesac.gojigi.user.auth.service.AuthService;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.exception.UserErrorCode;
import com.sesac.gojigi.user.user.exception.UserException;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 회원 — 탈퇴.
 * 탈퇴 = users 소프트 삭제(deleted_at, 이메일은 그대로 → 같은 이메일 재가입 불가) + Keycloak 계정 삭제.
 * 순서는 DB 먼저: Keycloak 삭제가 실패해도 탈퇴는 유지 — 남은 Keycloak 계정으로 로그인하면
 * 토큰의 user_id 가 탈퇴 회원이라 AUTH_ACCOUNT_NOT_LINKED 로 막힌다.
 * Keycloak 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션은 짧게 (TransactionTemplate).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final AuthService authService;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public void withdraw(Long userId) {
        User user = transactionTemplate.execute(status -> softDelete(userId));
        try {
            authService.deleteAccount(user.getKeycloakId(), user.getEmail());
        } catch (BusinessException e) {
            log.warn("탈퇴 후 Keycloak 계정 삭제 실패 — 로그인은 AUTH_ACCOUNT_NOT_LINKED 로 막힘 userId={}, code={}",
                    userId, e.getErrorCode().getCode());
        }
        log.info("회원 탈퇴 userId={}", userId);
    }

    private User softDelete(Long userId) {
        User user = userRepository.findByIdAndDeletedAtIsNull(userId)
                .orElseThrow(() -> new UserException(UserErrorCode.USER_NOT_FOUND));   // 없는 회원 · 이미 탈퇴
        // TODO 진행 중인 주문 · 교환 · 반품이 있으면 탈퇴 불가 — order-service 내부 API 가 생기면 여기서 확인
        user.softDelete(LocalDateTime.now(clock));
        return user;   // 트랜잭션 커밋 때 변경 감지로 UPDATE
    }
}
