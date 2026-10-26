package com.sesac.gojigi.admin;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.admin.report.entity.ReportStatus;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import com.sesac.gojigi.admin.report.repository.ReportRepository;
import com.sesac.gojigi.admin.review.client.ReviewClient;
import com.sesac.gojigi.admin.user.client.UserClient;
import com.sesac.gojigi.common.web.dto.ApiResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 신고 (10/26 B안) — 접수 · 대상별 묶음 · 상세 · 처리. review · user 클라이언트는 가짜, DB(admindb) · 서킷 · 오류 전달은 실제.
 * review-mock=false 로 실제 ReviewClientAdapter 경로를 탄다. 대상 ID 는 테스트마다 새로 (같은 컨텍스트가 DB 를 공유)
 */
@SpringBootTest(properties = { "management.tracing.sampling.probability=0", "app.client.review-mock=false" })
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminReportApiTest {

    private static final long ADMIN_ID = 1L;
    private static final long BUYER_ID = 4L;
    private static final long SELLER_ID = 2L;
    private static final long SELLER_STORE = 1L;
    private static final AtomicLong SEQ = new AtomicLong(9000);

    @Autowired MockMvc mockMvc;
    @Autowired JsonMapper json;
    @Autowired ReportRepository reportRepository;
    @Autowired CircuitBreakerRegistry circuitBreakers;
    @MockitoBean ReviewClient reviewClient;
    @MockitoBean UserClient userClient;

    @BeforeEach
    void reset() {
        org.mockito.Mockito.reset(reviewClient, userClient);
        circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    }

    private ApiResponse<JsonNode> ok(String data) {
        return ApiResponse.success("ok", json.readTree(data));
    }

    private static HttpClientErrorException rejected(HttpStatus status, String body) {
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY, body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    /** 리뷰 하나를 review 응답으로 흉내 — 작성자 · 스토어 지정 */
    private long review(long authorId, long storeId, String content) {
        long reviewId = SEQ.incrementAndGet();
        when(reviewClient.get(reviewId)).thenReturn(ok("""
                {"reviewId":%d,"productId":101,"storeId":%d,"userId":%d,"rating":1,"content":"%s","blinded":false,"blindReason":null}"""
                .formatted(reviewId, storeId, authorId, content)));
        return reviewId;
    }

    private ResultActions report(long userId, String roles, Long storeId, String body) throws Exception {
        var request = post("/api/reports").header("X-User-Id", userId).header("X-User-Roles", roles)
                .contentType(MediaType.APPLICATION_JSON).content(body);
        return mockMvc.perform(storeId == null ? request : request.header("X-Store-Id", storeId));
    }

    private static String reviewReport(long reviewId, String reason) {
        return "{\"targetType\":\"REVIEW\",\"targetId\":%d,\"reasonCode\":\"%s\",\"detail\":\" 욕설이 있어요 \"}".formatted(reviewId, reason);
    }

    private ResultActions resolve(String type, long targetId, String body) throws Exception {
        return mockMvc.perform(post("/api/admin/reports/{type}/{id}/resolve", type, targetId).header("X-User-Id", ADMIN_ID)
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("리뷰 신고 — 201 OPEN · 신고 당시 본문 요약, 같은 사람 재신고 409, 내 리뷰 400, 리뷰에 없는 사유 400, 없는 리뷰는 review 404 그대로")
    void reportReview_rules() throws Exception {
        long reviewId = review(77L, 3L, "별로예요 ㅡㅡ");

        report(BUYER_ID, "BUYER", null, reviewReport(reviewId, "ABUSE"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"));
        var saved = reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.REVIEW, reviewId).getFirst();
        org.assertj.core.api.Assertions.assertThat(saved.getTargetSummary()).isEqualTo("별로예요 ㅡㅡ");
        org.assertj.core.api.Assertions.assertThat(saved.getDetail()).isEqualTo("욕설이 있어요");
        org.assertj.core.api.Assertions.assertThat(saved.getReporterRole()).isEqualTo("BUYER");

        report(BUYER_ID, "BUYER", null, reviewReport(reviewId, "SPAM"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPORT_DUPLICATED"));
        report(77L, "BUYER", null, reviewReport(reviewId, "ABUSE"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_SELF"));
        report(5L, "BUYER", null, reviewReport(reviewId, "FRAUD"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_REASON_NOT_ALLOWED"));

        long missing = SEQ.incrementAndGet();
        when(reviewClient.get(missing)).thenThrow(rejected(HttpStatus.NOT_FOUND,
                "{\"code\":\"REVIEW_NOT_FOUND\",\"message\":\"리뷰를 찾을 수 없습니다.\",\"data\":null}"));
        report(BUYER_ID, "BUYER", null, reviewReport(missing, "ABUSE"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_FOUND"));
    }

    @Test
    @DisplayName("판매자 검토 요청 — SELLER · 내 스토어 리뷰만(다른 스토어 403), 구매자는 SELLER_REQUEST 를 쓸 수 없음")
    void sellerRequest_ownStoreOnly() throws Exception {
        long mine = review(BUYER_ID, SELLER_STORE, "배송이 느려요");
        long others = review(BUYER_ID, 9L, "배송이 느려요");

        report(SELLER_ID, "BUYER,SELLER", SELLER_STORE, reviewReport(mine, "SELLER_REQUEST")).andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThat(reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.REVIEW, mine)
                .getFirst().getReporterRole()).isEqualTo("SELLER");
        report(SELLER_ID, "BUYER,SELLER", SELLER_STORE, reviewReport(others, "SELLER_REQUEST"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("REPORT_NOT_OWN_STORE"));
        report(BUYER_ID + 1, "BUYER", null, reviewReport(mine, "SELLER_REQUEST"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_REASON_NOT_ALLOWED"));
    }

    @Test
    @DisplayName("회원 신고 — 본인 400, 요약은 이름 (닉네임)")
    void reportUser() throws Exception {
        long target = SEQ.incrementAndGet();
        when(userClient.detail(BUYER_ID, target)).thenReturn(ok("{\"userId\":%d,\"name\":\"홍길동\",\"nick\":\"길동이\"}".formatted(target)));

        report(BUYER_ID, "BUYER", null, "{\"targetType\":\"USER\",\"targetId\":%d,\"reasonCode\":\"FRAUD\"}".formatted(target))
                .andExpect(status().isCreated());
        org.assertj.core.api.Assertions.assertThat(reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.USER, target)
                .getFirst().getTargetSummary()).isEqualTo("홍길동 (길동이)");
        report(BUYER_ID, "BUYER", null, "{\"targetType\":\"USER\",\"targetId\":%d,\"reasonCode\":\"ABUSE\"}".formatted(BUYER_ID))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_SELF"));
    }

    @Test
    @DisplayName("관리자 — 대상별 묶음(열린 2건) → 상세 → 블라인드: review 에 사유 전달 · 열린 신고 2건 RESOLVED · 목록에 마지막 조치")
    void admin_listDetailBlind() throws Exception {
        long reviewId = review(77L, 3L, "광고 링크 http://spam");
        report(BUYER_ID, "BUYER", null, reviewReport(reviewId, "SPAM")).andExpect(status().isCreated());
        report(5L, "BUYER", null, reviewReport(reviewId, "ABUSE")).andExpect(status().isCreated());

        mockMvc.perform(get("/api/admin/reports").param("targetType", "REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.targetId == %d)].openCount".formatted(reviewId)).value(2))
                .andExpect(jsonPath("$.data[?(@.targetId == %d)].reasonCounts.SPAM".formatted(reviewId)).value(1));
        mockMvc.perform(get("/api/admin/reports/REVIEW/{id}", reviewId).header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target.content").value("광고 링크 http://spam"))
                .andExpect(jsonPath("$.data.reports.length()").value(2))
                .andExpect(jsonPath("$.data.actions.length()").value(0));

        when(reviewClient.blind(eq(reviewId), any())).thenReturn(ok("{\"reviewId\":%d,\"blinded\":true}".formatted(reviewId)));
        resolve("REVIEW", reviewId, "{\"action\":\"BLIND\",\"reason\":\"광고 · 스팸\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.closedReports").value(2))
                .andExpect(jsonPath("$.data.action.action").value("BLIND"))
                .andExpect(jsonPath("$.data.target.blinded").value(true));
        verify(reviewClient).blind(reviewId, json.readTree("{\"blinded\":true,\"reason\":\"광고 · 스팸\"}"));
        org.assertj.core.api.Assertions.assertThat(reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.REVIEW, reviewId))
                .allMatch(r -> r.getStatus() == ReportStatus.RESOLVED && r.getModerationActionId() != null);

        mockMvc.perform(get("/api/admin/reports"))
                .andExpect(jsonPath("$.data[?(@.targetId == %d)].openCount".formatted(reviewId)).value(0))
                .andExpect(jsonPath("$.data[?(@.targetId == %d)].lastAction.action".formatted(reviewId)).value("BLIND"));
    }

    @Test
    @DisplayName("회원 정지 — user 정지 API 에 {SUSPENDED, reason, days}, 해제는 {ACTIVE, reason} 로 열린 신고 없음 → 0건")
    void admin_suspendUser() throws Exception {
        long target = SEQ.incrementAndGet();
        when(userClient.detail(BUYER_ID, target)).thenReturn(ok("{\"userId\":%d,\"name\":\"사기꾼\"}".formatted(target)));
        report(BUYER_ID, "BUYER", null, "{\"targetType\":\"USER\",\"targetId\":%d,\"reasonCode\":\"FRAUD\"}".formatted(target));
        when(userClient.changeStatus(eq(ADMIN_ID), eq(target), any())).thenReturn(ok("{\"userId\":%d,\"status\":\"SUSPENDED\"}".formatted(target)));

        resolve("USER", target, "{\"action\":\"SUSPEND\",\"reason\":\"결제 도용 의심\",\"days\":7}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closedReports").value(1))
                .andExpect(jsonPath("$.data.action.suspendDays").value(7));
        verify(userClient).changeStatus(ADMIN_ID, target, json.readTree("{\"status\":\"SUSPENDED\",\"reason\":\"결제 도용 의심\",\"days\":7}"));

        resolve("USER", target, "{\"action\":\"UNSUSPEND\",\"reason\":\"소명 확인\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closedReports").value(0));
        verify(userClient).changeStatus(ADMIN_ID, target, json.readTree("{\"status\":\"ACTIVE\",\"reason\":\"소명 확인\"}"));
        mockMvc.perform(get("/api/admin/reports/USER/{id}", target).header("X-User-Id", ADMIN_ID))
                .andExpect(jsonPath("$.data.actions[0].action").value("UNSUSPEND"))
                .andExpect(jsonPath("$.data.actions[1].action").value("SUSPEND"));
    }

    @Test
    @DisplayName("처리 거절 — 대상에 맞지 않는 조치 · 정지가 아닌데 days 400, 기각은 대상 서비스 호출 없이 DISMISSED")
    void admin_resolveRules() throws Exception {
        long reviewId = review(77L, 3L, "그냥 그래요");
        report(BUYER_ID, "BUYER", null, reviewReport(reviewId, "ETC"));

        resolve("REVIEW", reviewId, "{\"action\":\"SUSPEND\",\"reason\":\"x\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_ACTION_NOT_ALLOWED"));
        resolve("REVIEW", reviewId, "{\"action\":\"BLIND\",\"reason\":\"x\",\"days\":3}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("REPORT_ACTION_NOT_ALLOWED"));
        resolve("REVIEW", reviewId, "{\"action\":\"DISMISS\",\"reason\":\"정상 리뷰\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.closedReports").value(1));
        verify(reviewClient, never()).blind(anyLong(), any());
        org.assertj.core.api.Assertions.assertThat(reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.REVIEW, reviewId)
                .getFirst().getStatus()).isEqualTo(ReportStatus.DISMISSED);
    }

    @Test
    @DisplayName("review 장애 — 블라인드 502, 조치를 남기지 않고 신고는 OPEN 그대로 · 상세는 신고만 보여 줌(targetError)")
    void admin_reviewDown_recordsNothing() throws Exception {
        long reviewId = review(77L, 3L, "욕설");
        report(BUYER_ID, "BUYER", null, reviewReport(reviewId, "ABUSE"));
        when(reviewClient.blind(eq(reviewId), any())).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));
        when(reviewClient.get(reviewId)).thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        resolve("REVIEW", reviewId, "{\"action\":\"BLIND\",\"reason\":\"욕설\"}")
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));
        org.assertj.core.api.Assertions.assertThat(reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(ReportTargetType.REVIEW, reviewId)
                .getFirst().getStatus()).isEqualTo(ReportStatus.OPEN);
        mockMvc.perform(get("/api/admin/reports/REVIEW/{id}", reviewId).header("X-User-Id", ADMIN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.target").doesNotExist())
                .andExpect(jsonPath("$.data.targetError").isNotEmpty())
                .andExpect(jsonPath("$.data.reports.length()").value(1));
    }
}
