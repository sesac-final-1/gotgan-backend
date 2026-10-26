package com.sesac.gojigi.admin;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 실제 @HttpExchange 클라이언트(AdminClientConfig) → 가짜 user · settlement · product · order · store 서버(JDK HttpServer) 까지 — 경로 · 쿼리 · 헤더 · 본문이
 * 내부 API 계약과 맞는지 확인한다. 가짜 서버는 받은 요청을 기록하고 받은 경로를 data 로 돌려준다.
 * 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)   // admindb(신고) 가 생겨 컨텍스트에 DB 가 필요
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminClientWiringTest {

    record Received(String method, String uri, String userId, String body) {
    }

    private static final List<Received> RECEIVED = new CopyOnWriteArrayList<>();
    private static final HttpServer FAKE = start();

    private static HttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/internal", exchange -> {
                String uri = exchange.getRequestURI().toString();
                RECEIVED.add(new Received(exchange.getRequestMethod(), uri, exchange.getRequestHeaders().getFirst("X-User-Id"),
                        new String(exchange.getRequestBody().readAllBytes(), UTF_8)));
                byte[] body = ("{\"code\":\"SUCCESS\",\"message\":\"ok\",\"data\":{\"path\":\"" + uri + "\"}}").getBytes(UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void clientUrls(DynamicPropertyRegistry registry) {
        String url = "http://127.0.0.1:" + FAKE.getAddress().getPort();
        registry.add("app.client.user-url", () -> url);
        registry.add("app.client.settlement-url", () -> url);
        registry.add("app.client.product-url", () -> url);
        registry.add("app.client.order-url", () -> url);
        registry.add("app.client.store-url", () -> url);
    }

    @AfterAll
    static void stop() {
        FAKE.stop(0);
    }

    @Autowired MockMvc mockMvc;

    @BeforeEach
    void clear() {
        RECEIVED.clear();
    }

    private Received last() {
        return RECEIVED.getLast();
    }

    @Test
    @DisplayName("회원 — 목록 · 상세 · 포인트 · 정지 경로와 관리자 X-User-Id 를 user 내부 API 그대로")
    void user_paths() throws Exception {
        mockMvc.perform(get("/api/admin/users").header("X-User-Id", 1).param("q", "kim").param("size", "20"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("/internal/users?q=kim&size=20"));
        assertThat(last().userId()).isEqualTo("1");

        mockMvc.perform(get("/api/admin/users/4").header("X-User-Id", 1))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("/internal/users/4"));

        mockMvc.perform(get("/api/admin/users/4/points").header("X-User-Id", 1).param("page", "0").param("size", "5"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.path").value("/internal/users/4/points?page=0&size=5"));

        mockMvc.perform(patch("/api/admin/users/4/status").header("X-User-Id", 1).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"스팸\",\"days\":7}"))
                .andExpect(status().isOk());
        assertThat(last()).satisfies(r -> {
            assertThat(r.method()).isEqualTo("PATCH");
            assertThat(r.uri()).isEqualTo("/internal/users/4/status");
            assertThat(r.userId()).isEqualTo("1");
            assertThat(r.body()).contains("\"days\":7").contains("\"reason\":\"스팸\"");
        });
    }

    @Test
    @DisplayName("정산 · 수수료 — 목록 · 상세 · 집계 · 지급 · 보류 · 수수료 경로 · 메서드 · 본문을 settlement 내부 API 그대로")
    void settlement_paths() throws Exception {
        mockMvc.perform(get("/api/admin/settlements").param("week", "2026-W43").param("storeId", "1"))
                .andExpect(jsonPath("$.data.path").value("/internal/settlements?week=2026-W43&storeId=1"));
        mockMvc.perform(get("/api/admin/settlements/12")).andExpect(jsonPath("$.data.path").value("/internal/settlements/12"));

        mockMvc.perform(post("/api/admin/settlements/aggregate")).andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("POST", "/internal/settlements/aggregate");

        mockMvc.perform(post("/api/admin/settlements/pay").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[12,13]}"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::uri, Received::body).containsExactly("/internal/settlements/pay", "{\"ids\":[12,13]}");

        mockMvc.perform(patch("/api/admin/settlements/12/hold").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hold\":true,\"reason\":\"분쟁\"}"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("PATCH", "/internal/settlements/12/hold");

        mockMvc.perform(get("/api/admin/commissions")).andExpect(jsonPath("$.data.path").value("/internal/commissions"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/commissions")
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"categoryId\":3,\"rate\":11.5}]"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("PUT", "/internal/commissions");
    }
    @Test
    @DisplayName("카테고리 · 대시보드 — product 카테고리 경로 · 메서드, order 전체 통계 · settlement 통계 경로")
    void category_dashboard_paths() throws Exception {
        mockMvc.perform(get("/api/admin/categories")).andExpect(jsonPath("$.data.path").value("/internal/categories"));

        mockMvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":3,\"name\":\"블라우스\"}"))
                .andExpect(status().isCreated());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("POST", "/internal/categories");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/categories/301")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"셔츠\",\"sortOrder\":1}"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("PUT", "/internal/categories/301");

        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.data.order.path").value("/internal/stats/orders"));
        assertThat(RECEIVED).extracting(Received::uri).contains("/internal/stats/settlements", "/internal/stats/orders");
    }
    @Test
    @DisplayName("입점 심사 · 스토어 — store.md 3~9절 경로 · 메서드 · 본문 그대로 (#97 계약)")
    void store_paths() throws Exception {
        mockMvc.perform(get("/api/admin/applications").param("status", "PENDING"))
                .andExpect(jsonPath("$.data.path").value("/internal/applications?status=PENDING"));
        mockMvc.perform(get("/api/admin/applications/27")).andExpect(jsonPath("$.data.path").value("/internal/applications/27"));
        mockMvc.perform(patch("/api/admin/applications/27/approve")).andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("PATCH", "/internal/applications/27/approve");
        mockMvc.perform(patch("/api/admin/applications/27/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"불일치\"}"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::uri, Received::body).containsExactly("/internal/applications/27/reject", "{\"reason\":\"불일치\"}");
        mockMvc.perform(patch("/api/admin/applications/27/supplement").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"흐림\",\"requestedDocs\":[]}"))
                .andExpect(status().isOk());
        assertThat(last().uri()).isEqualTo("/internal/applications/27/supplement");
        mockMvc.perform(get("/api/admin/stores")).andExpect(jsonPath("$.data.path").value("/internal/stores"));
        mockMvc.perform(get("/api/admin/stores/3")).andExpect(jsonPath("$.data.path").value("/internal/stores/3"));
        mockMvc.perform(patch("/api/admin/stores/3/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"신고\"}"))
                .andExpect(status().isOk());
        assertThat(last()).extracting(Received::method, Received::uri).containsExactly("PATCH", "/internal/stores/3/status");
    }
}
