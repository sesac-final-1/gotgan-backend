package com.sesac.gojigi.gateway.config;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;

/** 경로별 인가 — 401 · 403 은 업무 서비스와 같은 봉투. (라우팅 대상 서비스는 띄우지 않는다) */
@SpringBootTest(properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/gotgan")
class SecurityConfigTest {

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
    }

    @Test
    @DisplayName("API 응답 보안 헤더 — CSP(아무것도 못 불러옴 · 끼워 넣기 금지) · HSTS(http 로 와도) · nosniff · X-Frame-Options DENY, 401 응답에도")
    void apiResponses_haveSecurityHeaders() {
        client.get().uri("/actuator/health").exchange()
                .expectHeader().valueEquals("Content-Security-Policy", SecurityConfig.API_CSP)
                .expectHeader().valueEquals("Strict-Transport-Security", SecurityConfig.HSTS)
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY");
        client.delete().uri("/api/users/me").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Content-Security-Policy", SecurityConfig.API_CSP);
    }

    @Test
    @DisplayName("토큰 없이 인증 필요 경로 → 401 { code: UNAUTHORIZED }")
    void protectedPath_withoutToken_returns401Envelope() {
        client.delete().uri("/api/users/me").exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHORIZED")
                .jsonPath("$.message").isEqualTo("인증이 필요합니다.")
                .jsonPath("$.data").doesNotExist();
    }

    @Test
    @DisplayName("구매자가 판매자 경로 → 403 { code: FORBIDDEN }")
    void sellerPath_withBuyerRole_returns403Envelope() {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER")))
                .get().uri("/api/seller/settlements").exchange()
                .expectStatus().isForbidden()
                .expectBody()
                .jsonPath("$.code").isEqualTo("FORBIDDEN")
                .jsonPath("$.message").isEqualTo("권한이 없습니다.");
    }

    @Test
    @DisplayName("판매자가 관리자 경로 → 403")
    void adminPath_withSellerRole_returns403() {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_BUYER"), new SimpleGrantedAuthority("ROLE_SELLER")))
                .get().uri("/api/admin/users").exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("신고 접수는 구매자 · 판매자만 — 관리자 토큰만 있으면 403, 토큰 없으면 401")
    void reportPath_buyerOrSellerOnly() {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .post().uri("/api/reports").exchange()
                .expectStatus().isForbidden();
        client.post().uri("/api/reports").exchange()
                .expectStatus().isUnauthorized();
    }
}
