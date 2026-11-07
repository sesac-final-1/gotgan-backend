package com.sesac.gojigi.admin.system.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.admin.system.client.KubernetesClient.Pod;
import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.client.RestClient;

/** 쿠버네티스 REST 호출 — 가짜 API 서버(JDK HttpServer)로 경로 · Bearer 토큰 · 응답 해석 · 실패 → 503 확인 */
class InClusterKubernetesClientTest {

    private static final String NS = "/namespaces/sesac-gojigi";
    private static final String TOKEN = "test-token";

    @TempDir Path saDir;
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
        if (!("Bearer " + TOKEN).equals(ex.getRequestHeaders().getFirst("Authorization"))) {
            respond(ex, 401, "{}");
            return;
        }
        switch (path) {
            case "/apis/apps/v1" + NS + "/deployments" -> respond(ex, 200, """
                    {"items":[{"metadata":{"name":"order-service"},"spec":{"replicas":2}},{"metadata":{"name":"keycloak"},"spec":{}}]}""");
            case "/apis/apps/v1" + NS + "/statefulsets" -> respond(ex, 200, """
                    {"items":[{"metadata":{"name":"mysql"},"spec":{"replicas":1}}]}""");
            case "/api/v1" + NS + "/pods" -> respond(ex, 200, """
                    {"items":[
                      {"metadata":{"name":"order-service-a","labels":{"app":"order-service"},"creationTimestamp":"2026-11-09T05:00:00Z"},
                       "spec":{"nodeName":"ip-10-0-11-1"},
                       "status":{"phase":"Running","conditions":[{"type":"PodScheduled","status":"True"},{"type":"Ready","status":"True"}],
                                 "containerStatuses":[{"restartCount":2},{"restartCount":1}]}},
                      {"metadata":{"name":"order-service-b","labels":{"app":"order-service"},"creationTimestamp":"2026-11-09T05:01:00Z",
                                   "deletionTimestamp":"2026-11-09T05:02:00Z"},
                       "spec":{},
                       "status":{"phase":"Pending","conditions":[{"type":"Ready","status":"False"}]}}
                    ]}""");
            case "/api/v1" + NS + "/pods/order-service-a" -> respond(ex, 200, "{}");
            case "/api/v1" + NS + "/pods/gone" -> respond(ex, 404, "{}");
            default -> respond(ex, 403, "{\"reason\":\"Forbidden\"}");
        }
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private KubernetesClient client() {
        return new KubernetesClientConfig().kubernetesClient(RestClient.builder(), "http://127.0.0.1:" + server.getAddress().getPort(),
                "sesac-gojigi", saDir, Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    private void writeToken() throws IOException {
        Files.writeString(saDir.resolve("token"), TOKEN + "\n");
    }

    private static void assertUnavailable(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(SystemException.class)
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_CLUSTER_UNAVAILABLE);
    }

    @Test
    @DisplayName("원하는 레플리카 — Deployment · StatefulSet 이름별, replicas 가 없으면 1")
    void desiredReplicas() throws IOException {
        writeToken();
        assertThat(client().desiredReplicas()).containsEntry("order-service", 2).containsEntry("keycloak", 1).containsEntry("mysql", 1);
    }

    @Test
    @DisplayName("파드 — app 라벨 선택 · Ready 조건 · 재시작 합계 · 지워지는 중 · 노드 없음")
    void pods() throws IOException {
        writeToken();
        List<Pod> pods = client().pods();

        assertThat(requests).contains("GET /api/v1" + NS + "/pods?labelSelector=app");
        assertThat(pods).containsExactly(
                new Pod("order-service-a", "order-service", "ip-10-0-11-1", "Running", true, 3, Instant.parse("2026-11-09T05:00:00Z"), false),
                new Pod("order-service-b", "order-service", null, "Pending", false, 0, Instant.parse("2026-11-09T05:01:00Z"), true));
    }

    @Test
    @DisplayName("파드 삭제 — DELETE, 이미 없으면(404) 무시, 권한 없음(403)은 503")
    void deletePod() throws IOException {
        writeToken();
        KubernetesClient client = client();
        client.deletePod("order-service-a");
        assertThatCode(() -> client.deletePod("gone")).doesNotThrowAnyException();
        assertUnavailable(() -> client.deletePod("forbidden"));
        assertThat(requests).contains("DELETE /api/v1" + NS + "/pods/order-service-a");
    }

    @Test
    @DisplayName("토큰 파일이 없으면(클러스터 밖) — 503")
    void noToken_unavailable() {
        assertUnavailable(() -> client().pods());
        assertThat(requests).isEmpty();
    }

    @Test
    @DisplayName("API 서버에 닿지 못하면 — 503")
    void unreachable_unavailable() throws IOException {
        writeToken();
        KubernetesClient client = client();
        server.stop(0);
        assertUnavailable(client::desiredReplicas);
    }

    @Test
    @DisplayName("ca.crt 가 있으면 그 CA 로 SSLContext 를 만든다 · 깨진 파일이면 기동 실패")
    void caCertificate() throws IOException {
        try (InputStream in = getClass().getResourceAsStream("/system/test-ca.crt")) {
            Files.write(saDir.resolve("ca.crt"), in.readAllBytes());
        }
        assertThatCode(this::client).doesNotThrowAnyException();

        Files.writeString(saDir.resolve("ca.crt"), "not a certificate");
        assertThatThrownBy(this::client).isInstanceOf(IllegalStateException.class);
    }
}
