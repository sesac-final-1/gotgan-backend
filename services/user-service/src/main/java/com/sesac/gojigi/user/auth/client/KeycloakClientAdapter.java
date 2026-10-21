package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.client.dto.KeycloakUserCreateRequest;
import com.sesac.gojigi.user.auth.client.dto.KeycloakUserSummary;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Keycloak 호출 규칙 (가이드라인 08장):
 * - 서킷 브레이커 "keycloak" — 연결 실패 · 타임아웃 · 5xx 만 장애로 센다. OPEN 이면 바로 EXTERNAL_API_ERROR(502)
 * - 토큰 요청(POST)은 재시도하지 않는다 (Brute Force 실패 횟수가 이중으로 쌓임)
 * - invalid_grant(비밀번호 불일치 · 임시 잠금 · 정지)는 도메인 예외로 바꾸고 서킷 실패로 세지 않는다
 * - 계정 생성 · 삭제(Admin API)는 서비스 계정 토큰(client_credentials)으로 — 409 는 이메일 중복, 비밀번호 정책 위반 400 은 도메인 예외
 * - client 인증(client-id · secret)은 KeycloakClientConfig 가 Basic 헤더로 붙인다 — 여기서는 grant 별 폼만 만든다
 * - 비밀번호 · client secret · 토큰은 로그에 남기지 않는다
 * - 여기서는 로그를 찍지 않는다 — 원인을 예외에 실어 던지면 GlobalExceptionHandler 가 warn 한 번 (컨벤션 5-6)
 */
@Component
@RequiredArgsConstructor
public class KeycloakClientAdapter {

    private static final String CIRCUIT = "keycloak";
    private static final String INVALID_GRANT = "invalid_grant";
    private static final String ACCOUNT_DISABLED = "Account disabled";   // Keycloak 이 enabled=false 계정에 주는 설명

    private final KeycloakClient keycloakClient;
    private final KeycloakAdminClient keycloakAdminClient;
    private final JsonMapper jsonMapper;

    /** 로그인 — 이메일(= Keycloak username) · 비밀번호로 Direct Grant */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "tokenFallback")
    public KeycloakTokenResponse passwordGrant(String email, String password) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("username", email);
        form.add("password", password);
        try {
            return keycloakClient.token(form);
        } catch (HttpClientErrorException e) {
            throw toBusinessException(e);
        }
    }

    /**
     * 토큰 재발급 — Refresh Token 으로 새 Access · Refresh Token (realm 설정상 Refresh Token 은 한 번 쓰면 바뀐다).
     * 만료 · 로그아웃 · 이미 쓴 토큰(invalid_grant)은 AUTH_SESSION_EXPIRED.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "refreshGrantFallback")
    public KeycloakTokenResponse refreshGrant(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        try {
            return keycloakClient.token(form);
        } catch (HttpClientErrorException e) {
            if (INVALID_GRANT.equals(readBody(e).path("error").asString(""))) {
                throw new AuthException(AuthErrorCode.AUTH_SESSION_EXPIRED);
            }
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    /**
     * 소셜 로그인 콜백 — Keycloak 이 준 code 를 토큰으로 바꾼다 (PKCE code_verifier, 시작할 때와 같은 redirect_uri).
     * 만료 · 재사용 · 위조 code(invalid_grant)는 AUTH_SOCIAL_LOGIN_FAILED.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "authorizationCodeGrantFallback")
    public KeycloakTokenResponse authorizationCodeGrant(String code, String codeVerifier, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("code_verifier", codeVerifier);
        form.add("redirect_uri", redirectUri);
        try {
            return keycloakClient.token(form);
        } catch (HttpClientErrorException e) {
            if (INVALID_GRANT.equals(readBody(e).path("error").asString(""))) {
                throw new AuthException(AuthErrorCode.AUTH_SOCIAL_LOGIN_FAILED);
            }
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    /**
     * 소셜 첫 로그인 — Keycloak 이 만든 계정에 userdb 회원 번호(user_id 속성)를 붙인다. 다음 토큰부터 user_id 클레임이 들어간다.
     * PUT 은 보낸 표현으로 덮어써서, 현재 표현을 받아 attributes 만 바꿔 보낸다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "setUserIdAttributeFallback")
    @SuppressWarnings("unchecked")
    public void setUserIdAttribute(String keycloakId, long userId) {
        String bearer = "Bearer " + serviceAccountToken();
        try {
            Map<String, Object> user = new HashMap<>(keycloakAdminClient.getUser(bearer, keycloakId));
            Map<String, Object> attributes = new HashMap<>(
                    (Map<String, Object>) user.getOrDefault("attributes", Map.of()));
            attributes.put("user_id", List.of(String.valueOf(userId)));
            user.put("attributes", attributes);
            keycloakAdminClient.updateUser(bearer, keycloakId, user);
        } catch (HttpClientErrorException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    private KeycloakTokenResponse refreshGrantFallback(String refreshToken, Throwable t) {
        throw unavailable(t);
    }

    private KeycloakTokenResponse authorizationCodeGrantFallback(String code, String codeVerifier, String redirectUri, Throwable t) {
        throw unavailable(t);
    }

    private void setUserIdAttributeFallback(String keycloakId, long userId, Throwable t) {
        throw unavailable(t);
    }

    /**
     * 가입 — 계정 생성(username = email, 비밀번호, user_id 속성) 후 Keycloak 사용자 ID 를 돌려준다.
     * 역할 BUYER 는 realm 기본 역할이라 따로 부여하지 않는다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "createUserFallback")
    public String createUser(String email, String password, long userId) {
        String bearer = "Bearer " + serviceAccountToken();
        try {
            ResponseEntity<Void> response = keycloakAdminClient.createUser(bearer,
                    KeycloakUserCreateRequest.of(email, password, userId));
            return keycloakIdFrom(response.getHeaders().getLocation());
        } catch (HttpClientErrorException e) {
            throw toCreateUserException(e);
        }
    }

    /**
     * 로그아웃 — Refresh Token 의 Keycloak 세션 종료.
     * 이미 끝난 세션 · 만료 · 잘못된 토큰(invalid_grant)은 로그아웃된 것과 같으니 성공으로 본다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "logoutFallback")
    public void logout(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("refresh_token", refreshToken);
        try {
            keycloakClient.logout(form);
        } catch (HttpClientErrorException e) {
            if (INVALID_GRANT.equals(readBody(e).path("error").asString(""))) {
                return;   // Session not active · Token is not active · Invalid refresh token
            }
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    private void logoutFallback(String refreshToken, Throwable t) {
        throw unavailable(t);
    }

    /**
     * 탈퇴 — Keycloak 계정 삭제 (세션 · Refresh Token 도 함께 무효).
     * keycloakId 가 없으면(첫 로그인 전 회원) username 으로 찾는다. 이미 없으면(404 · 검색 결과 없음) 성공으로 본다.
     */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "deleteUserFallback")
    public void deleteUser(String keycloakId, String email) {
        String bearer = "Bearer " + serviceAccountToken();
        try {
            String targetId = keycloakId != null ? keycloakId : findIdByUsername(bearer, email);
            if (targetId == null) {
                return;
            }
            keycloakAdminClient.deleteUser(bearer, targetId);
        } catch (HttpClientErrorException.NotFound e) {
            // 이미 삭제됨 — 탈퇴 재시도 등
        } catch (HttpClientErrorException e) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    private String findIdByUsername(String bearer, String email) {
        return keycloakAdminClient.findByUsername(bearer, email, true).stream()
                .findFirst()
                .map(KeycloakUserSummary::id)
                .orElse(null);
    }

    private void deleteUserFallback(String keycloakId, String email, Throwable t) {
        throw unavailable(t);
    }

    private String serviceAccountToken() {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        try {
            return keycloakClient.serviceAccountToken(form).accessToken();
        } catch (HttpClientErrorException e) {
            // 서비스 계정 꺼짐 · client 인증 실패 — 설정 문제
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
        }
    }

    private String keycloakIdFrom(URI location) {
        String path = location == null ? "" : location.getPath();
        String id = path.substring(path.lastIndexOf('/') + 1);
        if (id.isBlank()) {
            throw new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 응답을 해석할 수 없습니다.");
        }
        return id;
    }

    private BusinessException toCreateUserException(HttpClientErrorException e) {
        if (e.getStatusCode().isSameCodeAs(HttpStatus.CONFLICT)) {
            return new AuthException(AuthErrorCode.AUTH_EMAIL_DUPLICATE);   // "User exists with same username"
        }
        // 정책 위반은 400 + errorMessage(invalidPassword...Message · 정책 설명) — 그 외 400 · 401 · 403 은 설정 문제
        String body = e.getResponseBodyAsString().toLowerCase();
        if (e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST) && body.contains("password")) {
            return new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
        }
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
    }

    private String createUserFallback(String email, String password, long userId, Throwable t) {
        throw unavailable(t);
    }

    private BusinessException toBusinessException(HttpClientErrorException e) {
        JsonNode body = readBody(e);
        String error = body.path("error").asString("");
        String description = body.path("error_description").asString("");
        if (INVALID_GRANT.equals(error)) {
            return new AuthException(ACCOUNT_DISABLED.equalsIgnoreCase(description)
                    ? AuthErrorCode.AUTH_ACCOUNT_DISABLED
                    : AuthErrorCode.AUTH_INVALID_CREDENTIALS);
        }
        // invalid_client(시크릿 불일치) · unauthorized_client(Direct Grant 꺼짐) 등은 설정 문제 — 사용자 탓이 아니다
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버 요청이 거절되었습니다.", e);
    }

    private JsonNode readBody(HttpClientErrorException e) {
        try {
            return jsonMapper.readTree(e.getResponseBodyAsString());
        } catch (RuntimeException parseFailure) {
            return jsonMapper.createObjectNode();
        }
    }

    private KeycloakTokenResponse tokenFallback(String email, String password, Throwable t) {
        throw unavailable(t);
    }

    private BusinessException unavailable(Throwable t) {
        if (t instanceof BusinessException business) {
            return business;   // 4xx 거절 — 이미 변환됨
        }
        // 연결 실패 · 타임아웃 · 5xx · 서킷 OPEN(CallNotPermittedException) — 폴백은 warn (핸들러가 cause 와 함께 기록)
        return new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "인증 서버에 연결할 수 없습니다.", t);
    }
}
