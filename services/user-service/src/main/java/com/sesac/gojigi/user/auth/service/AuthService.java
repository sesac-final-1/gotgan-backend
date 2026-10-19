package com.sesac.gojigi.user.auth.service;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증 — 비밀번호 검증 · 토큰 발급은 Keycloak, user-service 는 대리 호출 + userdb 회원 연결 (가이드라인 08장).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    private final KeycloakClientAdapter keycloakClientAdapter;
    private final UserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    /**
     * 로그인 — Keycloak Direct Grant 로 토큰을 받고, 토큰의 user_id 로 회원을 찾아 응답을 만든다.
     * Keycloak 호출은 트랜잭션 밖 · 회원 갱신(keycloak_id 연결 · 마지막 로그인)만 트랜잭션 — 외부 호출 동안 DB 커넥션을 잡지 않는다.
     */
    public LoginResult login(LoginRequest request) {
        KeycloakTokenResponse token = keycloakClientAdapter.passwordGrant(request.email(), request.password());
        AccessTokenClaims claims = AccessTokenClaims.parse(token.accessToken(), jsonMapper);
        User user = transactionTemplate.execute(status -> recordLogin(claims));
        LoginResponse response = LoginResponse.of(token.accessToken(), token.expiresIn(), user, claims);
        log.info("로그인 userId={} roles={}", user.getId(), claims.roles());   // 이메일 · 토큰은 남기지 않음 (컨벤션 5-6)

        return new LoginResult(response, token.refreshToken(), token.refreshExpiresIn());
    }

    private User recordLogin(AccessTokenClaims claims) {
        if (claims.userId() == null) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);   // Keycloak 속성 user_id 미설정
        }
        User user = userRepository.findByIdAndDeletedAtIsNull(claims.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED));
        if (!user.isActive()) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);   // userdb 정지 — Keycloak 동기화가 늦어도 막는다
        }
        if (user.getKeycloakId() == null) {
            user.linkKeycloak(claims.keycloakId());   // 시드 계정 · 가입 직후 미연결 — 첫 로그인 때 연결
            log.info("Keycloak 계정 연결 userId={}", user.getId());
        }
        user.recordLogin(LocalDateTime.now(clock));
        return user;   // 트랜잭션 커밋 때 변경 감지로 UPDATE
    }
}
