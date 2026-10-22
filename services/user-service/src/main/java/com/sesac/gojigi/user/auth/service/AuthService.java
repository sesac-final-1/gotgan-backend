package com.sesac.gojigi.user.auth.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.user.auth.client.KeycloakClientAdapter;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.component.AccessTokenClaims;
import com.sesac.gojigi.user.auth.component.SocialLoginState;
import com.sesac.gojigi.user.auth.component.SocialLoginUrls;
import com.sesac.gojigi.user.auth.component.SocialProvider;
import com.sesac.gojigi.user.auth.dto.request.LoginRequest;
import com.sesac.gojigi.user.auth.dto.request.SignupRequest;
import com.sesac.gojigi.user.auth.dto.response.LoginResponse;
import com.sesac.gojigi.user.auth.dto.result.LoginResult;
import com.sesac.gojigi.user.auth.dto.result.SignupResult;
import com.sesac.gojigi.user.auth.dto.result.SocialLoginResult;
import com.sesac.gojigi.user.auth.dto.result.SocialLoginStart;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
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
    private final SocialLoginUrls socialLoginUrls;
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
     * 토큰 재발급 — Refresh 쿠키로 새 Access Token 을 받고(Refresh Token 도 바뀐다) 로그인과 같은 응답을 만든다.
     * 쿠키 없음 · 만료 · 로그아웃된 세션은 AUTH_SESSION_EXPIRED. 탈퇴 · 정지 회원은 로그인과 같이 막는다 (마지막 로그인은 기록하지 않음).
     */
    public LoginResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_SESSION_EXPIRED);
        }
        KeycloakTokenResponse token = keycloakClientAdapter.refreshGrant(refreshToken);
        AccessTokenClaims claims = AccessTokenClaims.parse(token.accessToken(), jsonMapper);
        User user = transactionTemplate.execute(status -> loadLinkedActiveUser(claims));
        LoginResponse response = LoginResponse.of(token.accessToken(), token.expiresIn(), user, claims);
        return new LoginResult(response, token.refreshToken(), token.refreshExpiresIn());
    }

    /**
     * 소셜 로그인 시작 — state · PKCE 를 만들고 Keycloak 로그인 주소(kc_idp_hint=제공자)를 돌려준다.
     * 컨트롤러가 state 를 HttpOnly 쿠키에 담고 브라우저를 그 주소로 보낸다.
     */
    public SocialLoginStart startSocialLogin(String providerAlias) {
        SocialLoginState login = SocialLoginState.start(SocialProvider.fromAlias(providerAlias));
        return new SocialLoginStart(socialLoginUrls.authorizationUrl(login), login.cookieValue());
    }

    /**
     * 소셜 로그인 콜백 (keycloak README 소셜 로그인):
     * 1. 쿠키의 state 와 돌려받은 state 비교 · 제공자 오류 확인
     * 2. code → 토큰 (PKCE)
     * 3. 첫 소셜 로그인(토큰에 user_id 없음): users 행 생성 → Keycloak 계정에 user_id 저장 → 토큰 재발급(user_id 클레임 포함)
     *    같은 이메일의 자체 계정은 Keycloak 이 비밀번호 확인 후 연결해서 처음부터 user_id 가 있다
     * 4. 로그인 기록 (keycloak_id 연결 · 마지막 로그인)
     * 브라우저 리다이렉트로 끝나서 예외 대신 결과로 돌려준다 — 실패 원인은 여기서 warn 한 번.
     */
    public SocialLoginResult completeSocialLogin(String cookieValue, String code, String returnedState, String providerError) {
        SocialLoginState login = SocialLoginState.fromCookieValue(cookieValue);
        if (login == null || !login.matches(returnedState) || providerError != null || code == null) {
            log.warn("소셜 로그인 콜백 거절 — 쿠키 {} · state {} · 제공자 오류 {}",
                    login == null ? "없음/형식 오류" : "있음", login != null && login.matches(returnedState) ? "일치" : "불일치",
                    providerError);
            return SocialLoginResult.failure(AuthErrorCode.AUTH_SOCIAL_LOGIN_FAILED.getCode());
        }
        try {
            return SocialLoginResult.success(socialLogin(login, code));
        } catch (BusinessException e) {
            log.warn("소셜 로그인 실패 provider={}, code={}, message={}, cause={}", login.provider(),
                    e.getErrorCode().getCode(), e.getMessage(), String.valueOf(e.getCause()));
            return SocialLoginResult.failure(e.getErrorCode().getCode());
        }
    }

    private LoginResult socialLogin(SocialLoginState login, String code) {
        KeycloakTokenResponse token = keycloakClientAdapter.authorizationCodeGrant(code, login.codeVerifier(),
                socialLoginUrls.callbackUrl());
        AccessTokenClaims claims = AccessTokenClaims.parse(token.accessToken(), jsonMapper);
        if (claims.userId() == null) {
            Long userId = createSocialUserRow(claims, login.provider());
            try {
                keycloakClientAdapter.setUserIdAttribute(claims.keycloakId(), userId);
            } catch (RuntimeException e) {
                deleteUserRow(userId);
                throw e;
            }
            token = keycloakClientAdapter.refreshGrant(token.refreshToken());   // user_id 클레임이 들어간 토큰으로
            claims = AccessTokenClaims.parse(token.accessToken(), jsonMapper);
            log.info("소셜 첫 로그인 — 회원 생성 userId={}, provider={}", userId, login.provider());
        }
        AccessTokenClaims linked = claims;
        User user = transactionTemplate.execute(status -> recordLogin(linked));
        LoginResponse response = LoginResponse.of(token.accessToken(), token.expiresIn(), user, linked);
        log.info("소셜 로그인 userId={} provider={} roles={}", user.getId(), login.provider(), linked.roles());
        return new LoginResult(response, token.refreshToken(), token.refreshExpiresIn());
    }

    /**
     * 소셜 회원 행 — 이메일은 Keycloak 이 보장한다(없으면 첫 로그인 화면에서 입력). 이름이 없으면 이메일 앞부분.
     * 같은 이메일 행이 있으면 탈퇴 회원이다 — 탈퇴 때 Keycloak 계정(과 소셜 연결)이 지워져서 Keycloak 이 이번 로그인으로
     * 새 계정을 만든 상태. 행을 만들지 않고 그 Keycloak 계정을 지운 뒤 AUTH_EMAIL_DUPLICATE (재가입 불가, #36).
     * (살아 있는 자체 계정이면 Keycloak 이 비밀번호 확인 후 연결해서 처음부터 user_id 가 있다 — 여기로 오지 않는다)
     */
    private Long createSocialUserRow(AccessTokenClaims claims, SocialProvider provider) {
        if (claims.email() == null || claims.email().isBlank()) {
            throw new AuthException(AuthErrorCode.AUTH_SOCIAL_LOGIN_FAILED);
        }
        String email = claims.email().toLowerCase(Locale.ROOT);
        String name = claims.name() != null ? claims.name() : email.substring(0, email.indexOf('@'));
        try {
            return transactionTemplate.execute(status -> userRepository.saveAndFlush(
                    User.fromSocialLogin(claims.keycloakId(), email, name, provider.signupProvider())).getId());
        } catch (DataIntegrityViolationException e) {
            deleteWithdrawnMemberAccount(claims, provider);
            throw new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE);
        }
    }

    private void deleteWithdrawnMemberAccount(AccessTokenClaims claims, SocialProvider provider) {
        try {
            keycloakClientAdapter.deleteUser(claims.keycloakId(), null);
            log.warn("탈퇴한 이메일로 소셜 로그인 — 재가입 불가로 거절, 이번에 생긴 Keycloak 계정 삭제 provider={}, keycloakId={}",
                    provider, claims.keycloakId());
        } catch (RuntimeException e) {
            log.error("탈퇴한 이메일로 소셜 로그인 — 거절했지만 Keycloak 계정 삭제 실패. 로그인은 user_id 가 없어 계속 거절되나 "
                    + "관리 콘솔에서 계정 수동 삭제 필요 provider={}, keycloakId={}", provider, claims.keycloakId(), e);
        }
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

    /**
     * 비밀번호 확인 — 현재 비밀번호로 Direct Grant. 불일치 · 임시 잠금(AUTH_INVALID_CREDENTIALS)은 false, 정지 · 장애는 예외 그대로.
     * 확인하느라 생긴 Keycloak 세션은 바로 끝낸다 — 회원이 쓰던 세션 · 토큰과는 별개 (실패해도 확인 결과는 그대로).
     */
    public boolean verifyPassword(String email, String password) {
        KeycloakTokenResponse token;
        try {
            token = keycloakClientAdapter.passwordGrant(email, password);
        } catch (AuthException e) {
            if (e.getErrorCode() == AuthErrorCode.AUTH_INVALID_CREDENTIALS) {
                return false;
            }
            throw e;
        }
        try {
            keycloakClientAdapter.logout(token.refreshToken());
        } catch (BusinessException e) {
            log.warn("비밀번호 확인용 Keycloak 세션 종료 실패 — 확인 결과는 그대로, 세션은 만료까지 남음 code={}, cause={}",
                    e.getErrorCode().getCode(), String.valueOf(e.getCause()));
        }
        return true;
    }

    /** 비밀번호 변경 — 이메일과 같은 비밀번호는 realm 정책(notUsername)이라 먼저 막는다. 기존 세션 · 토큰은 그대로 둔다 */
    public void changePassword(String keycloakId, String email, String newPassword) {
        if (newPassword.equalsIgnoreCase(email)) {
            throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
        }
        keycloakClientAdapter.resetPassword(keycloakId, email, newPassword);
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
        log.warn("가입 보상 — Keycloak 계정 생성 · 연결 실패로 회원 행 삭제 userId={}", userId);
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

    /** 토큰의 user_id 로 연결된 활성 회원 — 연결 안 됨 · 탈퇴는 AUTH_ACCOUNT_NOT_LINKED, 정지는 AUTH_ACCOUNT_DISABLED */
    private User loadLinkedActiveUser(AccessTokenClaims claims) {
        if (claims.userId() == null) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED);   // Keycloak 속성 user_id 미설정
        }
        User user = userRepository.findByIdAndDeletedAtIsNull(claims.userId())
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_ACCOUNT_NOT_LINKED));
        if (!user.isActive()) {
            throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_DISABLED);   // userdb 정지 — Keycloak 동기화가 늦어도 막는다
        }
        return user;
    }

    private User recordLogin(AccessTokenClaims claims) {
        User user = loadLinkedActiveUser(claims);
        if (user.getKeycloakId() == null) {
            user.linkKeycloak(claims.keycloakId());   // 시드 계정 · 가입 직후 미연결 — 첫 로그인 때 연결
            log.info("Keycloak 계정 연결 userId={}", user.getId());
        }
        user.recordLogin(LocalDateTime.now(clock));
        return user;   // 트랜잭션 커밋 때 변경 감지로 UPDATE
    }
}
