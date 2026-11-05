package com.sesac.gojigi.admin.common.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.admin.settlement.client.SettlementBatchClient;
import com.sesac.gojigi.admin.settlement.client.SettlementClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 집계가 공통 읽기 시간(운영 3초)보다 오래 걸려도 집계 호출만은 끊지 않는다 — 11/5 k6 S6: 5,000건 5.6초 → 502 */
class AdminClientConfigTest {

    private static final Duration COMMON_READ = Duration.ofMillis(200);
    private static final Duration CONNECT = Duration.ofSeconds(1);
    private static final long SLOW_MILLIS = 800;

    private final AdminClientConfig config = new AdminClientConfig();
    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startSlowSettlement() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/internal/settlements", exchange -> {
            try {
                Thread.sleep(SLOW_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{\"data\":{\"created\":8}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    @DisplayName("공통 읽기 시간을 넘기는 집계도 집계 전용 클라이언트는 응답을 받는다")
    void aggregateClientOutlivesCommonReadTimeout() {
        SettlementBatchClient client = config.settlementBatchClient(commonBuilder(), baseUrl, CONNECT, Duration.ofSeconds(5));

        assertThat(client.aggregate(null).data().get("created").asInt()).isEqualTo(8);
    }

    @Test
    @DisplayName("집계 전용 읽기 시간도 넘기면 끊는다 — 무한 대기하지 않음")
    void aggregateClientStillHasLimit() {
        SettlementBatchClient client = config.settlementBatchClient(commonBuilder(), baseUrl, CONNECT, Duration.ofMillis(100));

        assertThatThrownBy(() -> client.aggregate(null)).isInstanceOf(ResourceAccessException.class);
    }

    @Test
    @DisplayName("다른 호출은 공통 읽기 시간을 그대로 쓴다 — 집계 전용 설정이 공통 빌더에 번지지 않음")
    void commonClientKeepsCommonReadTimeout() {
        RestClient.Builder common = commonBuilder();
        config.settlementBatchClient(common, baseUrl, CONNECT, Duration.ofSeconds(5));
        SettlementClient client = config.settlementClient(common, baseUrl);

        assertThatThrownBy(() -> client.list(null, null, null)).isInstanceOf(ResourceAccessException.class);
    }

    private static RestClient.Builder commonBuilder() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().build());
        factory.setReadTimeout(COMMON_READ);
        return RestClient.builder().requestFactory(factory);
    }
}
