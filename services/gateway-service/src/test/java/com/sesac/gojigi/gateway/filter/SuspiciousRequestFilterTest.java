package com.sesac.gojigi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;

class SuspiciousRequestFilterTest {

    @Test
    @DisplayName("탐색 경로를 유형별로 분류하고 정상 경로는 null")
    void classify() {
        assertThat(SuspiciousRequestFilter.classify("/.env")).isEqualTo("secret-file");
        assertThat(SuspiciousRequestFilter.classify("/app/.GIT/config")).isEqualTo("secret-file");
        assertThat(SuspiciousRequestFilter.classify("/api/../../etc/passwd")).isEqualTo("traversal");
        assertThat(SuspiciousRequestFilter.classify("/static/%2E%2E/x")).isEqualTo("traversal");
        assertThat(SuspiciousRequestFilter.classify("/wp-admin/setup.php")).isEqualTo("cms-probe");
        assertThat(SuspiciousRequestFilter.classify("/shell.php")).isEqualTo("script-probe");
        assertThat(SuspiciousRequestFilter.classify("/api/products/1")).isNull();
        assertThat(SuspiciousRequestFilter.classify(null)).isNull();
    }

    @Test
    @DisplayName("의심 요청은 카운터를 올리고 요청은 막지 않는다")
    void countsAndPassesThrough() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        SuspiciousRequestFilter filter = new SuspiciousRequestFilter(registry);
        boolean[] passed = {false, false};

        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/.env").build()),
                ex -> { passed[0] = true; return Mono.empty(); }).block();
        filter.filter(MockServerWebExchange.from(MockServerHttpRequest.get("/api/products").build()),
                ex -> { passed[1] = true; return Mono.empty(); }).block();

        assertThat(passed).containsExactly(true, true);
        assertThat(registry.get(SuspiciousRequestFilter.METRIC).tag("type", "secret-file").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(SuspiciousRequestFilter.METRIC).tag("type", "cms-probe").counter().count()).isZero();
    }

    @Test
    @DisplayName("로그용 경로는 줄바꿈을 없애고 200자로 자른다")
    void sanitize() {
        assertThat(SuspiciousRequestFilter.sanitize("/a\r\nb")).isEqualTo("/a__b");
        assertThat(SuspiciousRequestFilter.sanitize("/" + "x".repeat(300))).hasSize(203).endsWith("...");
    }
}
