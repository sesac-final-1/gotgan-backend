package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.common.exception.BusinessException;
import com.sesac.gojigi.user.common.exception.CommonErrorCode;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
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
    private final KeycloakProperties properties;
    private final JsonMapper jsonMapper;

    /** 로그인 — 이메일(= Keycloak username) · 비밀번호로 Direct Grant */
    @CircuitBreaker(name = CIRCUIT, fallbackMethod = "tokenFallback")
    public KeycloakTokenResponse passwordGrant(String email, String password) {
        MultiValueMap<String, String> form = clientForm("password");
        form.add("username", email);
        form.add("password", password);
        try {
            return keycloakClient.token(form);
        } catch (HttpClientErrorException e) {
            throw toBusinessException(e);
        }
    }

    private MultiValueMap<String, String> clientForm(String grantType) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", grantType);
        form.add("client_id", properties.clientId());
        form.add("client_secret", properties.clientSecret());
        return form;
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
