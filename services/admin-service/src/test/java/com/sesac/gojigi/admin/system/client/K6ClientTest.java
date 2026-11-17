package com.sesac.gojigi.admin.system.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.admin.system.exception.SystemErrorCode;
import com.sesac.gojigi.admin.system.exception.SystemException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** k6 REST API — 가짜 서버(JDK HttpServer)로 PATCH /v1/status 본문, 실패면 503 확인 */
class K6ClientTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/status", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    private K6Client client() {
        return new K6Client(RestClient.builder(), "http://{ip}:" + server.getAddress().getPort());
    }

    @Test
    @DisplayName("사용자 수 변경 — 파드 IP 주소로 PATCH /v1/status, 본문 data.attributes.vus")
    void scale() {
        client().scale("127.0.0.1", 40);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0)).startsWith("PATCH /v1/status ").contains("\"vus\":40").contains("\"type\":\"status\"");
    }

    @Test
    @DisplayName("k6 오류 응답, 연결 실패 — 503 SYSTEM_LOADTEST_CONTROL_FAILED")
    void scale_failures() {
        status = 500;
        assertThatThrownBy(() -> client().scale("127.0.0.1", 10)).isInstanceOf(SystemException.class)
                .extracting(e -> ((SystemException) e).getErrorCode()).isEqualTo(SystemErrorCode.SYSTEM_LOADTEST_CONTROL_FAILED);

        K6Client client = client();
        server.stop(0);
        assertThatThrownBy(() -> client.scale("127.0.0.1", 10)).isInstanceOf(SystemException.class);
    }
}
