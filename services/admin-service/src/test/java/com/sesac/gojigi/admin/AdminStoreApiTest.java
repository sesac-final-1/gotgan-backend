package com.sesac.gojigi.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.category.client.ProductClient;
import com.sesac.gojigi.admin.store.client.StoreClient;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 입점 심사 · 스토어 관리 — store.md 3~9절(#97 계약) 응답 · 오류 그대로 + 목록 · 상세 categoryName(product 카테고리).
 * store · product 클라이언트는 가짜, 서킷 · 재시도 · 오류 전달은 실제. 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)   // admindb(신고) 가 생겨 컨텍스트에 DB 가 필요
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminStoreApiTest {

    private static final String CATEGORIES = "[{\"id\":1,\"name\":\"식품\",\"children\":[{\"id\":101,\"name\":\"과일\"}]},"
            + "{\"id\":3,\"name\":\"패션의류\",\"children\":[]}]";

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper json;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @MockitoBean StoreClient storeClient;
    @MockitoBean ProductClient productClient;

    /** 목 · 서킷 초기화 — 장애 시험이 쌓여 서킷이 열리면 다음 테스트가 502 를 받는다 */
    @BeforeEach
    void resetMocks() {
        reset(storeClient, productClient);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ApiResponse<JsonNode> ok(String data) {
        return ApiResponse.success("ok", json.readTree(data));
    }

    private static HttpClientErrorException rejected(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"" + code + " 메시지\",\"data\":null}";
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("신청 목록 — status 그대로, 배열 각 항목에 상위 카테고리 이름을 붙인다")
    void applications_list_addsCategoryName() throws Exception {
        when(storeClient.applications("PENDING")).thenReturn(ok("[{\"applicationId\":27,\"categoryId\":1,\"status\":\"PENDING\"},"
                + "{\"applicationId\":28,\"categoryId\":3,\"status\":\"PENDING\"}]"));
        when(productClient.categories()).thenReturn(ok(CATEGORIES));

        mockMvc.perform(get("/api/admin/applications").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationId").value(27))
                .andExpect(jsonPath("$.data[0].categoryName").value("식품"))
                .andExpect(jsonPath("$.data[1].categoryName").value("패션의류"));
    }

    @Test
    @DisplayName("카테고리를 못 읽어도 심사는 계속 — 이름 없이 200 / 상세도 이름을 붙이고, 없는 신청은 404 그대로")
    void applications_productDown_stillOk() throws Exception {
        when(storeClient.applications(null)).thenReturn(ok("[{\"applicationId\":27,\"categoryId\":1}]"));
        when(productClient.categories()).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/applications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].applicationId").value(27))
                .andExpect(jsonPath("$.data[0].categoryName").doesNotExist());

        reset(productClient);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        when(productClient.categories()).thenReturn(ok(CATEGORIES));
        when(storeClient.application(27L)).thenReturn(ok("{\"applicationId\":27,\"categoryId\":1,\"sameBizRegNo\":{\"stores\":[]}}"));
        mockMvc.perform(get("/api/admin/applications/27"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.categoryName").value("식품"))
                .andExpect(jsonPath("$.data.sameBizRegNo.stores").isArray());

        when(storeClient.application(99L)).thenThrow(rejected(HttpStatus.NOT_FOUND, "APPLY_NOT_FOUND"));
        mockMvc.perform(get("/api/admin/applications/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLY_NOT_FOUND"));
    }

    @Test
    @DisplayName("승인 · 반려 · 보완요청 — 본문 그대로, 이미 처리된 신청 409 그대로, 승인은 재시도 없이 502")
    void applications_decisions() throws Exception {
        when(storeClient.approve(27L)).thenReturn(ok("{\"applicationId\":27,\"status\":\"APPROVED\",\"storeId\":3}"));
        mockMvc.perform(patch("/api/admin/applications/27/approve"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.storeId").value(3));

        when(storeClient.reject(eq(28L), any())).thenThrow(rejected(HttpStatus.CONFLICT, "APPLY_INVALID_STATUS"));
        mockMvc.perform(patch("/api/admin/applications/28/reject").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"정보 불일치\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("APPLY_INVALID_STATUS"));

        String supplement = "{\"message\":\"사본이 흐립니다\",\"requestedDocs\":[\"ECOMMERCE_REG\"]}";
        when(storeClient.supplement(eq(29L), any())).thenReturn(ok("{\"applicationId\":29,\"status\":\"SUPPLEMENT\",\"dueAt\":\"2026-11-02T10:00:00+09:00\"}"));
        mockMvc.perform(patch("/api/admin/applications/29/supplement").contentType(MediaType.APPLICATION_JSON).content(supplement))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.dueAt").value("2026-11-02T10:00:00+09:00"));
        verify(storeClient).supplement(29L, json.readTree(supplement));

        when(storeClient.approve(30L)).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(patch("/api/admin/applications/30/approve")).andExpect(status().isBadGateway());
        verify(storeClient, times(1)).approve(30L);
    }

    @Test
    @DisplayName("스토어 — 목록 · 상세 · 정지 그대로, 폐점 스토어 409 그대로, 목록 장애는 한 번 더 시도 후 502")
    void stores_relay() throws Exception {
        when(storeClient.stores("SUSPENDED")).thenReturn(ok("[{\"storeId\":1,\"status\":\"SUSPENDED\"}]"));
        mockMvc.perform(get("/api/admin/stores").param("status", "SUSPENDED"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].storeId").value(1));

        when(storeClient.store(1L)).thenReturn(ok("{\"storeId\":1,\"userId\":2,\"storeName\":\"곳간상회\",\"status\":\"ACTIVE\"}"));
        mockMvc.perform(get("/api/admin/stores/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.storeName").value("곳간상회"));

        when(storeClient.changeStoreStatus(eq(2L), any())).thenThrow(rejected(HttpStatus.CONFLICT, "STORE_INVALID_STATUS_TRANSITION"));
        mockMvc.perform(patch("/api/admin/stores/2/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"SUSPENDED\",\"reason\":\"허위 광고\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STORE_INVALID_STATUS_TRANSITION"));

        when(storeClient.stores(null)).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/stores")).andExpect(status().isBadGateway());
        verify(storeClient, times(2)).stores(null);
    }
}
