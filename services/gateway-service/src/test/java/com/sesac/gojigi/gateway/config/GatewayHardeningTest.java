package com.sesac.gojigi.gateway.config;

import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 코드리뷰 A-경-7 · A-경-6c · B-경-9 — 클레임 라우트 · 업무 서비스 타임아웃과 오류 봉투 · Keycloak 계정 콘솔 차단.
 * user · order · admin 라우트는 1초 뒤에 응답하는 느린 서버로, store · keycloak 은 아무도 듣지 않는 주소(기본값)로 보낸다.
 * 전체 응답 타임아웃은 테스트에서 300ms 로 줄인다 — order(15초) · SSE 스트림(끔)은 라우트 metadata 로 덮어 1초 응답도 받는다.
 */
@SpringBootTest(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/gotgan",
        "management.tracing.sampling.probability=0",
        "spring.cloud.gateway.server.webflux.httpclient.response-timeout=300ms"})
class GatewayHardeningTest {

    private static final DisposableServer SLOW = HttpServer.create().port(0)
            .handle((req, res) -> Mono.delay(Duration.ofSeconds(1)).then(res.sendString(Mono.just("ok")).then()))
            .bindNow();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        String slow = "http://localhost:" + SLOW.port();
        registry.add("USER_SERVICE_URI", () -> slow);
        registry.add("ORDER_SERVICE_URI", () -> slow);
        registry.add("ADMIN_SERVICE_URI", () -> slow);
    }

    @AfterAll
    static void stopSlowServer() {
        SLOW.disposeNow();
    }

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient()
                .responseTimeout(Duration.ofSeconds(10)).build();
    }

    private WebTestClient buyer() {
        return client.mutateWith(mockJwt().jwt(j -> j.claim("user_id", 4L)));
    }

    @Test
    @DisplayName("A-경-7 — /api/claims 는 order 로 간다 (404 가 아님). order 는 응답 타임아웃 15초라 1초 응답을 받는다")
    void claims_routedToOrder() {
        buyer().get().uri("/api/claims").exchange().expectStatus().isOk();
        buyer().patch().uri("/api/claims/9/withdraw").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("A-경-6c — 응답 타임아웃이면 504 EXTERNAL_API_ERROR 봉투 (기본 오류 JSON 이 아님)")
    void responseTimeout_504Envelope() {
        buyer().get().uri("/api/users/me").exchange()
                .expectStatus().isEqualTo(504)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EXTERNAL_API_ERROR")
                .jsonPath("$.message").isEqualTo("서비스 응답이 지연되고 있습니다. 잠시 후 다시 시도해 주세요.")
                .jsonPath("$.data").isEmpty()
                .jsonPath("$.timestamp").doesNotExist();
        client.mutateWith(mockJwt().jwt(j -> j.claim("user_id", 1L)).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                .get().uri("/api/admin/dashboard").exchange()
                .expectStatus().isEqualTo(504);
    }

    @Test
    @DisplayName("A-경-6c — 실시간 알림 SSE 스트림은 응답 타임아웃을 끈다 (전체 300ms 보다 오래 걸려도 끊지 않음)")
    void notificationStream_noResponseTimeout() {
        buyer().get().uri("/api/notifications/stream").exchange().expectStatus().isOk();
    }

    @Test
    @DisplayName("A-경-6c — 업무 서비스에 연결할 수 없으면 502 EXTERNAL_API_ERROR 봉투")
    void connectFailure_502Envelope() {
        client.get().uri("/api/stores/1").exchange()
                .expectStatus().isEqualTo(502)
                .expectBody()
                .jsonPath("$.code").isEqualTo("EXTERNAL_API_ERROR")
                .jsonPath("$.message").isEqualTo("서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.");
    }

    @Test
    @DisplayName("A-경-6c — 맞는 라우트가 없으면 404 NOT_FOUND 봉투")
    void noRoute_404Envelope() {
        buyer().get().uri("/api/nothing-here").exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.code").isEqualTo("NOT_FOUND")
                .jsonPath("$.message").isEqualTo("요청한 리소스를 찾을 수 없습니다.");
    }

    @Test
    @DisplayName("B-경-9 — Keycloak 계정 콘솔 · 동적 클라이언트 등록은 막힌다 (비로그인 401 · 로그인 403)")
    void keycloakAccountConsole_blocked() {
        client.get().uri("/realms/gotgan/account").exchange().expectStatus().isUnauthorized();
        client.get().uri("/realms/gotgan/account/").exchange().expectStatus().isUnauthorized();
        client.get().uri("/realms/gotgan/account/#/personal-info").exchange().expectStatus().isUnauthorized();
        client.post().uri("/realms/gotgan/clients-registrations/openid-connect").exchange().expectStatus().isUnauthorized();
        buyer().get().uri("/realms/gotgan/account").exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("FORBIDDEN");
    }

    @Test
    @DisplayName("B-경-9 — 소셜 로그인에 쓰는 Keycloak 경로는 그대로 통과 (Keycloak 이 없어 502 — 막힌 게 아님)")
    void keycloakLoginPaths_stillOpen() {
        client.get().uri("/realms/gotgan/protocol/openid-connect/auth?client_id=gotgan-backend").exchange()
                .expectStatus().isEqualTo(502);
        client.get().uri("/realms/gotgan/broker/kakao/endpoint?code=x").exchange()
                .expectStatus().isEqualTo(502);
        client.get().uri("/realms/gotgan/login-actions/first-broker-login").exchange()
                .expectStatus().isEqualTo(502);
    }
}
