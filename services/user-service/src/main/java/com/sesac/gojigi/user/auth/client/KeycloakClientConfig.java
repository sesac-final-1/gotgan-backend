package com.sesac.gojigi.user.auth.client;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/**
 * Keycloak 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제한다.
 * - KeycloakClient: realm OIDC 주소 + client 인증 헤더(Basic)
 * - KeycloakAdminClient: Admin API 주소, 인증은 호출마다 서비스 계정 Bearer 토큰 (Basic 헤더를 붙이지 않는다)
 */
@Configuration
public class KeycloakClientConfig {

    @Bean
    public KeycloakClient keycloakClient(RestClient.Builder restClientBuilder,
            @Value("${app.keycloak.base-url}") String baseUrl,
            @Value("${app.keycloak.realm}") String realm,
            @Value("${app.keycloak.client-id}") String clientId,
            @Value("${app.keycloak.client-secret:}") String clientSecret) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(baseUrl + "/realms/" + realm)
                // client 인증은 Basic 헤더(client_secret_basic) — RFC 6749 2.3.1 대로 URL 인코딩 후 Base64. 시크릿은 환경 변수로만, 로그 금지
                .defaultHeaders(headers -> headers.setBasicAuth(urlEncode(clientId), urlEncode(clientSecret)))
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(KeycloakClient.class);
    }

    @Bean
    public KeycloakAdminClient keycloakAdminClient(RestClient.Builder restClientBuilder,
            @Value("${app.keycloak.base-url}") String baseUrl,
            @Value("${app.keycloak.realm}") String realm) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(baseUrl + "/admin/realms/" + realm)
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(KeycloakAdminClient.class);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
