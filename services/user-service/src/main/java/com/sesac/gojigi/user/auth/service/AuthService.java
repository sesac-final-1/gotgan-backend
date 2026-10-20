package com.sesac.gojigi.user.auth.service;

import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.request.SignupRequest;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * 인증 — 비밀번호 검증 · 토큰 발급 · 계정 저장은 Keycloak, user-service 는 대리 호출 + userdb 회원 연결 (가이드라인 08장).
 * Keycloak 호출 동안 DB 커넥션을 잡지 않도록 트랜잭션은 단계별로 짧게 (TransactionTemplate).
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

    /** 이메일 중복 확인 — 탈퇴 회원 이메일도 사용 중으로 본다 (uk_users_email) */
    public boolean isEmailAvailable(String normalizedEmail) {
        return !userRepository.existsByEmail(normalizedEmail);
    }

    /**
     * 회원가입 (users.keycloak_id 주석 · keycloak README):
     * 1. userdb 행 생성 (커밋해야 user_id 가 나온다) → 2. Keycloak 계정 생성(user_id 속성) → 3. keycloak_id 연결 → 4. 자동 로그인.
     * 2 가 실패하면 1 의 행을 지운다 (가입 실패 보상 — 회원 물리 삭제 금지의 유일한 예외).
     * 3 · 4 의 실패는 가입을 되돌리지 않는다 — keycloak_id 는 첫 로그인 때도 연결되고, 로그인은 다시 하면 된다.
     */
    public SignupResult signup(SignupRequest request) {
        String email = request.normalizedEmail();
        if (request.password().equalsIgnoreCase(email)) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);   // realm 정책 notUsername
        }
        if (!isEmailAvailable(email)) {
            throw new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE);
        }

        Long userId = createUserRow(email, request.trimmedName(), request.normalizedPhone());
        String keycloakId;
        try {
            keycloakId = keycloakClientAdapter.createUser(email, request.password(), userId);
        } catch (RuntimeException e) {
            deleteUserRow(userId);
            throw e;
        }
        linkKeycloak(userId, keycloakId);
        log.info("회원가입 userId={}", userId);

        return autoLogin(userId, email, request.password());
    }

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

    /**
     * 로그아웃 — Keycloak 세션을 끝내 Refresh Token 을 무효로 한다. 쿠키 삭제는 컨트롤러.
     * 쿠키가 없거나 Keycloak 이 실패해도 예외를 던지지 않는다 — 브라우저 쪽 로그아웃(쿠키 삭제)은 항상 끝나야 한다.
     * Access Token 은 만료(최대 30분)까지 남는다 — 프론트가 메모리에서 버리는 것으로 끝 (blacklist:{jti} 는 Could).
     *
     * @return Keycloak 세션을 끝냈으면 true (쿠키 없음 · 실패면 false)
     */
    public boolean logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            log.info("로그아웃 — Refresh 쿠키 없음, 쿠키 삭제만");
            return false;
        }
        Long userId = userIdForLog(refreshToken);
        try {
            keycloakClientAdapter.logout(refreshToken);
            log.info("로그아웃 userId={}, keycloakSessionEnded=true", userId);
            return true;
        } catch (BusinessException e) {
            log.warn("로그아웃 중 Keycloak 세션 종료 실패 — 쿠키는 삭제. 브라우저에선 로그아웃됐지만 세션은 Refresh 만료(최대 14일)까지 남음 "
                    + "(쿠키가 사라져 쓸 수 없음, 탈취된 경우만 위험) userId={}, code={}, cause={}",
                    userId, e.getErrorCode().getCode(), String.valueOf(e.getCause()));
            return false;
        }
    }

    /**
     * 로그 전용 — Refresh Token(JWT)의 sub(Keycloak 사용자 ID)로 회원을 찾는다. 서명 검증 없이 읽으므로 판단에 쓰지 않는다
     * (세션 종료 판단은 Keycloak 이 한다). 읽을 수 없는 토큰 · 연결 안 된 계정이면 null.
     */
    private Long userIdForLog(String refreshToken) {
        try {
            String keycloakId = AccessTokenClaims.parse(refreshToken, jsonMapper).keycloakId();
            return keycloakId == null ? null : userRepository.findByKeycloakId(keycloakId).map(User::getId).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * 탈퇴 시 Keycloak 계정 삭제 — 로그인 · Refresh 가 더 이상 되지 않는다.
     * 회원 행 처리 · 실패 시 정책은 호출하는 쪽(UserService.withdraw)이 정한다.
     */
    public void deleteAccount(String keycloakId, String email) {
        keycloakClientAdapter.deleteUser(keycloakId, email);
    }

    private Long createUserRow(String email, String name, String phone) {
        try {
            return transactionTemplate.execute(status ->
                    userRepository.saveAndFlush(User.signUp(email, name, null, phone)).getId());
        } catch (DataIntegrityViolationException e) {
            throw new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE);   // 동시 가입 — 중복 확인 뒤 다른 요청이 먼저 저장
        }
    }

    private void deleteUserRow(Long userId) {
        log.warn("가입 보상 — Keycloak 계정 생성 실패로 회원 행 삭제 userId={}", userId);
        transactionTemplate.execute(status -> {
            userRepository.deleteById(userId);
            return null;
        });
    }

    private void linkKeycloak(Long userId, String keycloakId) {
        try {
            transactionTemplate.execute(status -> {
                userRepository.findById(userId).ifPresent(user -> user.linkKeycloak(keycloakId));
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("가입 후 keycloak_id 연결 실패 — 첫 로그인 때 연결 userId={}, cause={}", userId, e.toString());
        }
    }

    private SignupResult autoLogin(Long userId, String email, String password) {
        try {
            return SignupResult.loggedIn(userId, login(new LoginRequest(email, password)));
        } catch (BusinessException e) {
            log.warn("가입 후 자동 로그인 실패 — 로그인 화면으로 userId={}, code={}", userId, e.getErrorCode().getCode());
            return SignupResult.withoutLogin(userId);
        }
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
