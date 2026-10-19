package com.sesac.gojigi.user.auth.client;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.keycloak.* — realm 주소 · gotgan-backend 클라이언트 인증 정보.
 * client-secret 은 환경 변수(KEYCLOAK_CLIENT_SECRET)로만 들어오므로 toString 에서 가린다 (컨벤션 1-5 · 5-6).
 */
@ConfigurationProperties(prefix = "app.keycloak")
public record KeycloakProperties(String baseUrl, String realm, String clientId, String clientSecret) {

    public String realmUrl() {
        return baseUrl + "/realms/" + realm;
    }

    @Override
    public String toString() {
        return "KeycloakProperties[baseUrl=" + baseUrl + ", realm=" + realm + ", clientId=" + clientId + ", clientSecret=***]";
    }
}
