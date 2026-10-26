package com.sesac.gojigi.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.settlement.client.SettlementClient;
import com.sesac.gojigi.admin.user.client.UserClient;
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
 * admin 중계 — 관리자 회원(user) · 정산 · 수수료(settlement). 다른 서비스 클라이언트는 가짜, 서킷 브레이커 · 재시도 · 오류 전달은 실제.
 * 트레이스 샘플링 0 — 같은 JVM 의 LogbackConfigTest 에 Zipkin 경고가 섞이지 않게 (product #90 과 같은 이유)
 */
@SpringBootTest(properties = "management.tracing.sampling.probability=0")
@Import(TestcontainersConfiguration.class)   // admindb(신고) 가 생겨 컨텍스트에 DB 가 필요
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRelayApiTest {

    private static final long ADMIN_ID = 1L;

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper json;
    @MockitoBean UserClient userClient;
    @MockitoBean SettlementClient settlementClient;
    @Autowired CircuitBreakerRegistry circuitBreakers;

    /** 목 · 서킷 초기화 — 장애 시험이 쌓여 서킷이 열리면 다음 테스트가 502 를 받는다 */
    @BeforeEach
    void resetMocks() {
        reset(userClient, settlementClient);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ApiResponse<JsonNode> ok(String data) {
        return ApiResponse.success("ok", json.readTree(data));
    }

    private static HttpClientErrorException rejected(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("회원 목록 — 검색 조건 · 관리자 X-User-Id 를 그대로 넘기고 응답 data 를 그대로 돌려준다")
    void users_list_relaysParamsAndData() throws Exception {
        when(userClient.list(ADMIN_ID, "kim", "SUSPENDED", 0, 20))
                .thenReturn(ok("{\"content\":[{\"userId\":4,\"name\":\"김구매\"}],\"page\":0,\"totalElements\":1}"));

        mockMvc.perform(get("/api/admin/users").header("X-User-Id", ADMIN_ID).header("X-User-Roles", "ADMIN")
                        .param("q", "kim").param("status", "SUSPENDED").param("page", "0").param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.content[0].userId").value(4))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("정지 · 해제 — 본문 그대로 전달, user 의 4xx 는 HTTP 상태 · code · message 그대로")
    void users_changeStatus_relaysBodyAndError() throws Exception {
        when(userClient.changeStatus(eq(ADMIN_ID), eq(4L), any())).thenReturn(ok("{\"userId\":4,\"status\":\"SUSPENDED\",\"suspendedUntil\":null}"));
        String body = "{\"status\":\"SUSPENDED\",\"reason\":\"스팸\"}";

        mockMvc.perform(patch("/api/admin/users/4/status").header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        verify(userClient).changeStatus(eq(ADMIN_ID), eq(4L), eq(json.readTree(body)));

        when(userClient.changeStatus(eq(ADMIN_ID), eq(5L), any())).thenThrow(rejected(HttpStatus.CONFLICT,
                "{\"code\":\"USER_INVALID_STATUS_TRANSITION\",\"message\":\"이미 정지된 회원입니다.\",\"data\":null}"));
        mockMvc.perform(patch("/api/admin/users/5/status").header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_INVALID_STATUS_TRANSITION"))
                .andExpect(jsonPath("$.message").value("이미 정지된 회원입니다."));
    }

    @Test
    @DisplayName("연결 실패 — GET 은 한 번 더 시도 후 502, PATCH 는 재시도 없이 502")
    void users_unavailable_retriesGetOnly() throws Exception {
        when(userClient.detail(ADMIN_ID, 4L)).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/users/4").header("X-User-Id", ADMIN_ID))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
        verify(userClient, times(2)).detail(ADMIN_ID, 4L);

        when(userClient.changeStatus(eq(ADMIN_ID), eq(4L), any())).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(patch("/api/admin/users/4/status").header("X-User-Id", ADMIN_ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"해제\"}"))
                .andExpect(status().isBadGateway());
        verify(userClient, times(1)).changeStatus(eq(ADMIN_ID), eq(4L), any());

        when(userClient.list(ADMIN_ID, null, null, null, null)).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/users").header("X-User-Id", ADMIN_ID)).andExpect(status().isBadGateway());
        when(userClient.points(ADMIN_ID, 4L, null, null)).thenThrow(new ResourceAccessException("connect timed out"));
        mockMvc.perform(get("/api/admin/users/4/points").header("X-User-Id", ADMIN_ID)).andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("봉투가 아닌 4xx 본문은 502 — 대상 서비스 앞단 오류를 관리자 화면 code 로 오해하지 않게")
    void nonEnvelopeRejection_is502() throws Exception {
        when(settlementClient.detail(9L)).thenThrow(rejected(HttpStatus.NOT_FOUND, "<html>Not Found</html>"));

        mockMvc.perform(get("/api/admin/settlements/9"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
        verify(settlementClient, times(1)).detail(9L);   // 4xx 는 재시도하지 않음
    }

    @Test
    @DisplayName("정산 — 목록 조건 전달 · 본문 없는 집계 · 일괄 지급 결과 그대로 · 보류 사유 없음 400 그대로")
    void settlements_relay() throws Exception {
        when(settlementClient.list("2026-W43", null, "HELD")).thenReturn(ok("{\"week\":\"2026-W43\",\"settlements\":[]}"));
        mockMvc.perform(get("/api/admin/settlements").param("week", "2026-W43").param("status", "HELD"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.week").value("2026-W43"));

        when(settlementClient.aggregate(isNull())).thenReturn(ok("{\"week\":\"2026-W43\",\"created\":2}"));
        mockMvc.perform(post("/api/admin/settlements/aggregate"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(2));

        when(settlementClient.pay(any())).thenReturn(ok("{\"success\":[1],\"failed\":[{\"id\":2,\"code\":\"SETTLE_PAYOUT_ACCOUNT_MISSING\"}]}"));
        mockMvc.perform(post("/api/admin/settlements/pay").contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[1,2]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.success[0]").value(1))
                .andExpect(jsonPath("$.data.failed[0].code").value("SETTLE_PAYOUT_ACCOUNT_MISSING"));

        when(settlementClient.hold(eq(1L), any())).thenThrow(rejected(HttpStatus.BAD_REQUEST,
                "{\"code\":\"SETTLE_HOLD_REASON_REQUIRED\",\"message\":\"보류 사유를 입력해 주세요.\",\"data\":null}"));
        mockMvc.perform(patch("/api/admin/settlements/1/hold").contentType(MediaType.APPLICATION_JSON).content("{\"hold\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SETTLE_HOLD_REASON_REQUIRED"));
    }

    @Test
    @DisplayName("수수료 — 조회 · 변경 그대로, 지난 날짜 400 그대로")
    void commissions_relay() throws Exception {
        when(settlementClient.commissions()).thenReturn(ok("[{\"categoryId\":3,\"current\":{\"rate\":11.00}}]"));
        mockMvc.perform(get("/api/admin/commissions"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].current.rate").value(11.00));

        when(settlementClient.updateCommissions(any())).thenThrow(rejected(HttpStatus.BAD_REQUEST,
                "{\"code\":\"COMMISSION_PAST_EFFECTIVE_DATE\",\"message\":\"적용 시작일은 오늘 이후여야 합니다.\",\"data\":null}"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/commissions")
                        .contentType(MediaType.APPLICATION_JSON).content("[{\"categoryId\":3,\"rate\":12,\"effectiveFrom\":\"2026-01-01\"}]"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMISSION_PAST_EFFECTIVE_DATE"));
    }
}
