package com.sesac.gojigi.user.auth.client;

import com.sesac.gojigi.user.auth.client.dto.KeycloakServiceAccountToken;
import com.sesac.gojigi.user.auth.client.dto.KeycloakTokenResponse;
import org.springframework.http.MediaType;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * Keycloak realm gotgan 의 OIDC 엔드포인트 ({base-url}/realms/gotgan). 클라이언트 gotgan-backend (confidential).
 * 직접 쓰지 않고 KeycloakClientAdapter(서킷 브레이커 "keycloak")를 거친다.
 */
@HttpExchange("/protocol/openid-connect")
public interface KeycloakClient {

    /** 토큰 발급 — grant_type=password(로그인) · refresh_token(재발급) */
    @PostExchange(url = "/token", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    KeycloakTokenResponse token(@RequestBody MultiValueMap<String, String> form);

    /** 서비스 계정 토큰 — grant_type=client_credentials (Admin API 호출용, refresh_token 없음) */
    @PostExchange(url = "/token", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    KeycloakServiceAccountToken serviceAccountToken(@RequestBody MultiValueMap<String, String> form);

    /** 로그아웃 — refresh_token 의 Keycloak 세션을 끝낸다 (204). 그 세션의 Refresh Token 은 더 이상 쓸 수 없다 */
    @PostExchange(url = "/logout", contentType = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    void logout(@RequestBody MultiValueMap<String, String> form);
}
