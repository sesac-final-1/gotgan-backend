package com.sesac.gojigi.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.category.client.ProductClient;
import com.sesac.gojigi.admin.dashboard.client.OrderClient;
import com.sesac.gojigi.admin.product.client.AdminProductClient;
import com.sesac.gojigi.admin.settlement.client.SettlementClient;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 카테고리 조합(product + settlement 수수료) · 대시보드 부분 응답(정산 · 주문 · 일별 · 상품). 시각 고정 2026-10-26 KST — 신규 상위 수수료 적용 시작일 = 오늘.
 * 트레이스 샘플링 0 — LogbackConfigTest 에 Zipkin 경고가 섞이지 않게
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, AdminCategoryDashboardApiTest.FixedClock.class})
@ActiveProfiles("test")
class AdminCategoryDashboardApiTest {

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(Instant.parse("2026-10-26T01:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper json;
    @MockitoBean ProductClient productClient;
    @MockitoBean SettlementClient settlementClient;
    @MockitoBean OrderClient orderClient;
    @MockitoBean AdminProductClient adminProductClient;
    @Autowired CircuitBreakerRegistry circuitBreakers;

    /** 목 · 서킷 초기화 — 장애 시험이 쌓여 서킷이 열리면 다음 테스트가 502 를 받는다 */
    @BeforeEach
    void resetMocks() {
        reset(productClient, settlementClient, orderClient, adminProductClient);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ApiResponse<JsonNode> ok(String data) {
        return ApiResponse.success("ok", json.readTree(data));
    }

    @Test
    @DisplayName("상위 생성 — product 생성(사용 중지) → 수수료(오늘부터) → 사용으로, 최종 응답은 켜진 카테고리")
    void createTop_registersCommissionThenActivates() throws Exception {
        when(productClient.create(any())).thenReturn(ok("{\"id\":7,\"parentId\":null,\"name\":\"반려동물\",\"active\":false}"));
        when(settlementClient.updateCommissions(any())).thenReturn(ok("[]"));
        when(productClient.changeActive(eq(7L), any())).thenReturn(ok("{\"id\":7,\"parentId\":null,\"name\":\"반려동물\",\"active\":true}"));

        mockMvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"반려동물\",\"nameEn\":\"Pet\",\"commissionRate\":8.5}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(7))
                .andExpect(jsonPath("$.data.active").value(true));
        ArgumentCaptor<JsonNode> commission = ArgumentCaptor.forClass(JsonNode.class);
        verify(settlementClient).updateCommissions(commission.capture());
        JsonNode row = commission.getValue().get(0);   // 율은 BigDecimal 이라 숫자 값으로 비교
        assertThat(row.path("categoryId").asLong()).isEqualTo(7L);
        assertThat(row.path("rate").decimalValue()).isEqualByComparingTo("8.5");
        assertThat(row.path("effectiveFrom").asString()).isEqualTo("2026-10-26");
        verify(productClient).changeActive(7L, json.readTree("{\"active\":true}"));
    }

    @Test
    @DisplayName("상위인데 수수료율 없음 400 — product 를 부르지 않음 / 하위는 수수료 없이 product 생성만")
    void createRules() throws Exception {
        mockMvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"반려동물\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CATEGORY_COMMISSION_RATE_REQUIRED"));
        verify(productClient, never()).create(any());

        when(productClient.create(any())).thenReturn(ok("{\"id\":307,\"parentId\":3,\"name\":\"블라우스\",\"active\":true}"));
        mockMvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"parentId\":3,\"name\":\"블라우스\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.parentId").value(3));
        verify(settlementClient, never()).updateCommissions(any());
    }

    @Test
    @DisplayName("상위 생성 중 수수료 등록이 실패하면 502 CATEGORY_ACTIVATION_PENDING — 사용 처리는 하지 않음(꺼진 채 남음)")
    void createTop_commissionFails_leavesInactive() throws Exception {
        when(productClient.create(any())).thenReturn(ok("{\"id\":8,\"parentId\":null,\"active\":false}"));
        when(settlementClient.updateCommissions(any())).thenThrow(new ResourceAccessException("connect timed out"));

        mockMvc.perform(post("/api/admin/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"취미\",\"commissionRate\":9}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("CATEGORY_ACTIVATION_PENDING"));
        verify(productClient, never()).changeActive(any(), any());
    }

    @Test
    @DisplayName("수정 — 사용 여부가 그대로면 PUT 만, 상위를 켤 때 현재 수수료가 없으면 409, 있으면 PATCH")
    void update_activationNeedsCommission() throws Exception {
        when(productClient.update(eq(5L), any())).thenReturn(ok("{\"id\":5,\"parentId\":null,\"name\":\"뷰티\",\"active\":false}"));

        mockMvc.perform(put("/api/admin/categories/5").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"뷰티\",\"sortOrder\":5}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        verify(productClient, never()).changeActive(any(), any());

        when(settlementClient.commissions()).thenReturn(ok("[{\"categoryId\":5,\"current\":null,\"reserved\":[{\"rate\":10}]}]"));
        mockMvc.perform(put("/api/admin/categories/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"뷰티\",\"sortOrder\":5,\"active\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_COMMISSION_REQUIRED"));

        when(settlementClient.commissions()).thenReturn(ok("[{\"categoryId\":5,\"current\":{\"rate\":10.00,\"effectiveFrom\":\"2026-10-19\"}}]"));
        when(productClient.changeActive(eq(5L), any())).thenReturn(ok("{\"id\":5,\"parentId\":null,\"active\":true}"));
        mockMvc.perform(put("/api/admin/categories/5").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"뷰티\",\"sortOrder\":5,\"active\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(true));
    }

    @Test
    @DisplayName("하위를 끄고 켜는 건 수수료 확인 없이 PATCH")
    void update_child_noCommissionCheck() throws Exception {
        when(productClient.update(eq(301L), any())).thenReturn(ok("{\"id\":301,\"parentId\":3,\"active\":true}"));
        when(productClient.changeActive(eq(301L), any())).thenReturn(ok("{\"id\":301,\"parentId\":3,\"active\":false}"));

        mockMvc.perform(put("/api/admin/categories/301").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"셔츠\",\"sortOrder\":1,\"active\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.active").value(false));
        verify(settlementClient, never()).commissions();
    }

    @Test
    @DisplayName("대시보드 — 네 영역(정산 · 주문 · 최근 30일 일별 · 상품) 모두, 장애난 영역만 null + unavailable 로 200")
    void dashboard_partial() throws Exception {
        when(settlementClient.platformStats()).thenReturn(ok("{\"store\":null,\"platform\":{\"scheduledCount\":3,\"heldCount\":1}}"));
        when(orderClient.platformStats()).thenReturn(ok("{\"totalTradeAmt\":1200000,\"orderCount\":42}"));
        when(orderClient.dailyStats(30)).thenReturn(ok("[{\"date\":\"2026-10-26\",\"tradeAmt\":89000,\"orderCount\":2}]"));
        when(adminProductClient.platformStats())
                .thenReturn(ok("{\"store\":null,\"platform\":{\"onSaleCount\":12,\"lowStockCount\":2,\"soldOutCount\":1,\"blockedCount\":1}}"));

        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlement.scheduledCount").value(3))
                .andExpect(jsonPath("$.data.order.orderCount").value(42))
                .andExpect(jsonPath("$.data.orderDaily[0].tradeAmt").value(89000))
                .andExpect(jsonPath("$.data.product.onSaleCount").value(12))
                .andExpect(jsonPath("$.data.product.store").doesNotExist())
                .andExpect(jsonPath("$.data.unavailable").isEmpty());

        when(orderClient.platformStats()).thenThrow(new ResourceAccessException("connect timed out"));
        when(adminProductClient.platformStats()).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.settlement.heldCount").value(1))
                .andExpect(jsonPath("$.data.order").doesNotExist())
                .andExpect(jsonPath("$.data.orderDaily[0].orderCount").value(2))
                .andExpect(jsonPath("$.data.product").doesNotExist())
                .andExpect(jsonPath("$.data.unavailable[0]").value("order"))
                .andExpect(jsonPath("$.data.unavailable[1]").value("product"));
    }
}
