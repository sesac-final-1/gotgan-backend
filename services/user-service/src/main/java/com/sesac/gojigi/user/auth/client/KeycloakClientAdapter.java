package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.client.dto.KeycloakUserCreateRequest;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import java.net.URI;
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
 * - 계정 생성(Admin API)은 서비스 계정 토큰(client_credentials)으로 — 409 는 이메일 중복, 비밀번호 정책 위반 400 은 도메인 예외
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
