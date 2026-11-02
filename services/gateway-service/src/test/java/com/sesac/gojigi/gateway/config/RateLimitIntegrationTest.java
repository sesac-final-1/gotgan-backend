package com.sesac.gojigi.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

/**
 * 요청 제한 — 실제 Redis(토큰 버킷 Lua) · 라우트 설정(yml 앵커) · 키 규칙까지. 라우팅 대상 서비스는 띄우지 않는다(429 가 아니면 통과한 것).
 * default 한도는 테스트에서 3회로 줄이고 충전을 아주 느리게 — 반복 중에 토큰이 다시 차서 결과가 흔들리지 않게.
 */
@SpringBootTest(properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:1/realms/gotgan",
        "management.tracing.sampling.probability=0",
        "app.rate-limit.default.replenish-rate=1", "app.rate-limit.default.burst-capacity=300",
        "app.rate-limit.default.requested-tokens=100"})
class RateLimitIntegrationTest {

    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    static {
        REDIS.start();
    }

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void stopRedis() {
        REDIS.stop();
    }

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() throws Exception {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
        REDIS.execInContainer("redis-cli", "FLUSHALL");   // 테스트마다 버킷을 비운다
    }

    /** 로그인 POST 를 n 번 — 각 응답 상태 */
    private List<HttpStatus> login(int times, String vercelIp) {
        List<HttpStatus> statuses = new ArrayList<>();
        for (int i = 0; i < times; i++) {
            WebTestClient.RequestHeadersSpec<?> request = client.post().uri("/api/auth/login");
            if (vercelIp != null) {
                request = request.header("X-Vercel-Forwarded-For", vercelIp);
            }
            statuses.add(HttpStatus.valueOf(request.exchange().returnResult(Void.class).getStatus().value()));
        }
        return statuses;
    }

    @Test
    @DisplayName("로그인 — 같은 Vercel IP 는 10회까지 통과, 11번째 429 + X-RateLimit 헤더. 다른 IP · 직접 호출(direct)은 따로 센다")
    void auth_perIp_tenPerMinute() {
        List<HttpStatus> sameIp = login(10, "210.205.48.210");

        assertThat(sameIp).doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        client.post().uri("/api/auth/login").header("X-Vercel-Forwarded-For", "210.205.48.210").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists("X-RateLimit-Remaining")   // 남은 "토큰" 수 — 요청 1건이 6개를 써서 6 미만이면 거절(그사이 충전돼 0 이 아닐 수 있음)
                .expectHeader().exists("X-RateLimit-Burst-Capacity");
        assertThat(login(1, "203.0.113.7")).doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(login(1, null)).doesNotContain(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("X-Forwarded-For 를 바꿔 보내도 우회되지 않는다 — 직접 호출은 모두 공용 키 direct")
    void auth_forwardedForSpoof_doesNotBypass() {
        for (int i = 0; i < 10; i++) {
            client.post().uri("/api/auth/login").header("X-Forwarded-For", "1.2.3." + i).exchange();
        }

        client.post().uri("/api/auth/login").header("X-Forwarded-For", "9.9.9.9").exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("일반 API — 로그인 사용자 기준(테스트 한도 3회), 다른 사용자는 따로. 라우트가 다르면 버킷도 따로")
    void default_perUser_perRoute() {
        WebTestClient user4 = client.mutateWith(mockJwt().jwt(j -> j.claim("user_id", 4L)));
        for (int i = 0; i < 3; i++) {
            assertThat(user4.get().uri("/api/orders").exchange().returnResult(Void.class).getStatus())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        user4.get().uri("/api/orders").exchange().expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(user4.get().uri("/api/carts").exchange().returnResult(Void.class).getStatus())   // 같은 order 라우트 — 이미 소진
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(user4.get().uri("/api/users/me").exchange().returnResult(Void.class).getStatus())   // user-service 라우트 — 따로
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(client.mutateWith(mockJwt().jwt(j -> j.claim("user_id", 5L)))
                .get().uri("/api/orders").exchange().returnResult(Void.class).getStatus())
                .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("포트원 웹훅은 요청 제한 없음 — 같은 출처에서 계속 와도 429 가 나지 않는다")
    void webhook_notLimited() {
        for (int i = 0; i < 10; i++) {
            assertThat(client.post().uri("/api/payments/webhook").exchange().returnResult(Void.class).getStatus())
                    .isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }
    }
}
