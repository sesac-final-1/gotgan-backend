package com.sesac.gojigi.user.auth.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 소셜 로그인 리다이렉트 주소 — 브라우저가 이동하는 주소라 모두 공개 주소다.
 * - Keycloak 로그인: {public-url}/realms/{realm}/protocol/openid-connect/auth (클러스터: https://api.gotgan.click — gateway 가 /realms/gotgan/** 를 Keycloak 으로)
 * - 콜백: Keycloak 이 code 를 붙여 돌려보내는 우리 주소 — realm 클라이언트 redirectUris 에 등록돼 있어야 한다
 * - 결과: 로그인 후 프론트 페이지 (실패면 ?error={코드})
 * user-service → Keycloak 토큰 교환은 내부 주소(app.keycloak.base-url)로 한다.
 */
@Component
public class SocialLoginUrls {

    private final String authorizationEndpoint;
    private final String clientId;
    private final String callbackUrl;
    private final String resultUrl;

    public SocialLoginUrls(@Value("${app.keycloak.public-url}") String keycloakPublicUrl,
            @Value("${app.keycloak.realm}") String realm,
            @Value("${app.keycloak.client-id}") String clientId,
            @Value("${app.auth.social.callback-url}") String callbackUrl,
            @Value("${app.auth.social.result-url}") String resultUrl) {
        this.authorizationEndpoint = keycloakPublicUrl + "/realms/" + realm + "/protocol/openid-connect/auth";
        this.clientId = clientId;
        this.callbackUrl = callbackUrl;
        this.resultUrl = resultUrl;
    }

    /** Keycloak 로그인 주소 — kc_idp_hint 로 Keycloak 화면을 건너뛰고 바로 제공자 로그인으로 */
    public String authorizationUrl(SocialLoginState login) {
        return UriComponentsBuilder.fromUriString(authorizationEndpoint)
                .queryParam("client_id", clientId)
                .queryParam("response_type", "code")
                .queryParam("scope", "openid")
                .queryParam("redirect_uri", callbackUrl)
                .queryParam("state", login.state())
                .queryParam("code_challenge", login.codeChallenge())
                .queryParam("code_challenge_method", "S256")
                .queryParam("kc_idp_hint", login.provider().alias())
                .encode()
                .toUriString();
    }

    public String callbackUrl() {
        return callbackUrl;
    }

    public String successUrl() {
        return resultUrl;
    }

    public String failureUrl(String errorCode) {
        return UriComponentsBuilder.fromUriString(resultUrl).queryParam("error", errorCode).encode().toUriString();
    }
}
