package com.sesac.gojigi.settlement.settlement.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sesac.gojigi.settlement.TestcontainersConfiguration;
import com.sesac.gojigi.settlement.common.event.EventOutbox;
import com.sesac.gojigi.settlement.common.event.EventOutboxRepository;
import com.sesac.gojigi.settlement.common.event.EventOutboxService;
import com.sesac.gojigi.settlement.common.event.SnsEventPublisher;
import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.repository.PayoutRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementItemRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementHistoryRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementRepository;
import com.sesac.gojigi.settlement.settlement.repository.SettlementStoreRepository;
import com.sesac.gojigi.settlement.settlement.service.StoreStatusChangedListener;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import org.springframework.test.web.servlet.ResultActions;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 정산 2단계 — 내부 API(관리자) · 판매자 API · 수수료 API 를 HTTP 로. 실제 MySQL · Flyway V1~V5.
 * 시각 고정: 2026-10-26(월) 10:00 KST → 지난주 = 2026-W43(10/19~10/25), 다음 주 월요일 = 11/02.
 * SNS 발행 · S3 presign 은 가짜 (CI 에 AWS 자격 증명 없음). 테스트마다 다른 storeId 를 써서 서로 영향이 없게 한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, SettlementApiIntegrationTest.FixedClock.class})
@ActiveProfiles("test")
class SettlementApiIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(5000);
    private static final String W43 = "2026-W43";

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            ZoneId kst = ZoneId.of("Asia/Seoul");
            return Clock.fixed(ZonedDateTime.of(2026, 10, 26, 10, 0, 0, 0, kst).toInstant(), kst);
        }
    }

    @Autowired MockMvc mockMvc;
    @Autowired SettlementStoreRepository settlementStoreRepository;
    @Autowired SettlementItemRepository settlementItemRepository;
    @Autowired SettlementRepository settlementRepository;
    @Autowired SettlementHistoryRepository settlementHistoryRepository;
    @Autowired PayoutRepository payoutRepository;
    @Autowired StoreStatusChangedListener storeStatusChangedListener;
    @Autowired EventOutboxRepository eventOutboxRepository;
    @Autowired EventOutboxService eventOutboxService;
    @Autowired JsonMapper jsonMapper;
    @MockitoBean SnsEventPublisher snsEventPublisher;
    @MockitoBean S3Presigner s3Presigner;
    @MockitoBean S3Client s3Client;

    // ── 도우미

    private List<EventOutbox> outboxOf(long settlementId) {
        return eventOutboxRepository.findAll().stream()
                .filter(o -> o.getEventType().equals("SettlementPaid") && o.getAggregateId().equals(String.valueOf(settlementId)))
                .toList();
    }

    private long store(boolean withAccount) {
        long storeId = SEQ.incrementAndGet();
        settlementStoreRepository.save(SettlementStore.approved(storeId, "스토어" + storeId, "s" + storeId + "@example.com",
                withAccount ? "기업은행" : null, withAccount ? "010-0000-8812" : null, withAccount ? "예금주" : null));
        return storeId;
    }

    /** W43 구매확정 상품주문 — 11% · 2.2% 내림 금액으로 */
    private void item(long storeId, long itemAmt, LocalDateTime confirmedAt) {
        settlementItemRepository.save(SettlementItem.builder()
                .orderItemId(SEQ.incrementAndGet()).orderNo("20261020-000001").storeId(storeId).productId(77L)
                .productName("린넨 셔츠").categoryId(1L).itemAmt(itemAmt).commissionRate(new BigDecimal("11.00"))
                .commissionAmt(itemAmt * 11 / 100).paymentFeeAmt(itemAmt * 22 / 1000).confirmedAt(confirmedAt).build());
    }

    private ResultActions aggregate() throws Exception {
        return mockMvc.perform(post("/internal/settlements/aggregate"));
    }

    private ResultActions aggregateWeek(String isoWeek) throws Exception {
        return mockMvc.perform(post("/internal/settlements/aggregate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"week\":\"" + isoWeek + "\"}"));
    }

    private static final LocalDate W42_START = LocalDate.of(2026, 10, 12);

    private Settlement settlementOf(long storeId) {
        return settlementOf(storeId, LocalDate.of(2026, 10, 19));
    }

    private Settlement settlementOf(long storeId, LocalDate weekStart) {
        return settlementRepository.findByStoreIdAndWeekStart(storeId, weekStart).orElseThrow();
    }

    private ResultActions pay(long... ids) throws Exception {
        StringBuilder json = new StringBuilder("{\"ids\":[");
        for (int i = 0; i < ids.length; i++) {
            json.append(i == 0 ? "" : ",").append(ids[i]);
        }
        return mockMvc.perform(post("/internal/settlements/pay").contentType(MediaType.APPLICATION_JSON).content(json.append("]}").toString()));
    }

    private ResultActions hold(long id, boolean hold, String reason) throws Exception {
        String body = reason == null ? "{\"hold\":" + hold + "}" : "{\"hold\":" + hold + ",\"reason\":\"" + reason + "\"}";
        return mockMvc.perform(patch("/internal/settlements/" + id + "/hold").contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions asSeller(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, long storeId) throws Exception {
        return mockMvc.perform(req.header("X-User-Id", "7").header("X-User-Roles", "SELLER").header("X-Store-Id", String.valueOf(storeId)));
    }

    private void storeStatus(long storeId, String status, String occurredAt) {
        storeStatusChangedListener.onMessage("""
                {"eventId":"%s","eventType":"StoreStatusChanged","occurredAt":"%s","aggregateId":"x","version":1,
                 "payload":{"storeId":%d,"status":"%s"}}
                """.formatted(UUID.randomUUID(), occurredAt, storeId, status));
    }

    // ── 집계

    @Nested
    class Aggregate {

        @Test
        @DisplayName("지난주(W43) 구매확정분을 스토어별로 묶어 합계 · 지급 예정일(목) — 두 번 돌려도 1건 (멱등)")
        void aggregate_groupsByStore_idempotent() throws Exception {
            long storeId = store(true);
            item(storeId, 59_000L, LocalDateTime.of(2026, 10, 19, 0, 0));     // 월 00:00 포함
            item(storeId, 12_345L, LocalDateTime.of(2026, 10, 25, 23, 59));   // 일 23:59 포함
            item(storeId, 1_000L, LocalDateTime.of(2026, 10, 26, 0, 0));      // 다음 주 — 제외

            aggregate().andExpect(status().isOk()).andExpect(jsonPath("$.data.week").value(W43));
            aggregate().andExpect(status().isOk()).andExpect(jsonPath("$.data.created").value(0));

            Settlement s = settlementOf(storeId);
            assertThat(s.getItemCount()).isEqualTo(2);
            assertThat(s.getSalesAmt()).isEqualTo(71_345L);
            assertThat(s.getCommissionAmt()).isEqualTo(6_490L + 1_357L);
            assertThat(s.getPaymentFeeAmt()).isEqualTo(1_298L + 271L);
            assertThat(s.getPayoutAmt()).isEqualTo(71_345L - 7_847L - 1_569L);
            assertThat(s.getScheduledPayDate()).isEqualTo(LocalDate.of(2026, 10, 29));
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
        }

        @Test
        @DisplayName("집계 뒤 늦게 들어온 구매확정은 지급 전 정산서에 더해 다시 계산")
        void aggregate_lateItem_recalculates() throws Exception {
            long storeId = store(true);
            item(storeId, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            item(storeId, 20_000L, LocalDateTime.of(2026, 10, 21, 12, 0));

            aggregate().andExpect(jsonPath("$.data.updated").value(greaterThanOrEqualTo(1)));
            assertThat(settlementOf(storeId).getSalesAmt()).isEqualTo(30_000L);
        }

        @Test
        @DisplayName("휴면 · 정지 스토어는 집계 때 자동 보류 — 이력: 주간 집계 → 자동 보류")
        void aggregate_heldStore_autoHeld() throws Exception {
            long storeId = store(true);
            storeStatus(storeId, "SUSPENDED", "2026-10-22T10:00:00+09:00");
            item(storeId, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));

            aggregate();

            Settlement s = settlementOf(storeId);
            assertThat(s.getStatus()).isEqualTo(SettlementStatus.HELD);
            mockMvc.perform(get("/internal/settlements/" + s.getId()))
                    .andExpect(jsonPath("$.data.history.length()").value(2))
                    .andExpect(jsonPath("$.data.history[1].to").value("HELD"))
                    .andExpect(jsonPath("$.data.history[1].actor").value("SYSTEM"));
        }

        @Test
        @DisplayName("지급 완료된 주(W42)에 늦게 들어온 구매확정 → 다음 집계 주차(W43) 정산서로 이월 · 이월 n건 표기 (결정 2번 A안)")
        void aggregate_lateItemOfPaidWeek_carriedOver() throws Exception {
            long storeId = store(true);
            item(storeId, 10_000L, LocalDateTime.of(2026, 10, 13, 12, 0));
            aggregateWeek("2026-W42");
            pay(settlementOf(storeId, W42_START).getId());
            item(storeId, 20_000L, LocalDateTime.of(2026, 10, 14, 12, 0));   // W42 지급 후 도착
            item(storeId, 30_000L, LocalDateTime.of(2026, 10, 21, 12, 0));   // W43 본래 분

            aggregate().andExpect(jsonPath("$.data.carriedOver").value(greaterThanOrEqualTo(1)));

            assertThat(settlementOf(storeId, W42_START).getSalesAmt()).isEqualTo(10_000L);   // 지급된 정산서는 그대로
            Settlement w43 = settlementOf(storeId);
            assertThat(w43.getItemCount()).isEqualTo(2);
            assertThat(w43.getCarriedOverCount()).isEqualTo(1);
            assertThat(w43.getSalesAmt()).isEqualTo(50_000L);
            mockMvc.perform(get("/internal/settlements/" + w43.getId()))
                    .andExpect(jsonPath("$.data.settlement.carriedOverCount").value(1))
                    .andExpect(jsonPath("$.data.items[0].carriedOver").value(true))     // 구매확정일 순 — 10/14 이월분 먼저
                    .andExpect(jsonPath("$.data.items[1].carriedOver").value(false));
        }

        @Test
        @DisplayName("지급 전인 지난 주(W42)에 늦게 들어온 구매확정 → 원래 주 정산서에 더해 다시 계산 (이월 아님)")
        void aggregate_lateItemOfUnpaidWeek_addedToOwnWeek() throws Exception {
            long storeId = store(true);
            item(storeId, 10_000L, LocalDateTime.of(2026, 10, 13, 12, 0));
            aggregateWeek("2026-W42");
            item(storeId, 20_000L, LocalDateTime.of(2026, 10, 14, 12, 0));

            aggregate().andExpect(jsonPath("$.data.updated").value(greaterThanOrEqualTo(1)));

            Settlement w42 = settlementOf(storeId, W42_START);
            assertThat(w42.getSalesAmt()).isEqualTo(30_000L);
            assertThat(w42.getCarriedOverCount()).isZero();
            assertThat(settlementRepository.findByStoreIdAndWeekStart(storeId, LocalDate.of(2026, 10, 19))).isEmpty();
        }
    }

    // ── 보류 · 지급

    @Nested
    class HoldAndPay {

        private long storeId;
        private long settlementId;

        @BeforeEach
        void aggregated() throws Exception {
            storeId = store(true);
            item(storeId, 59_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            settlementId = settlementOf(storeId).getId();
        }

        @Test
        @DisplayName("보류는 사유 필수(400) · 보류 중 지급은 실패 목록 · 해제 후 지급 가능")
        void hold_release_pay() throws Exception {
            hold(settlementId, true, null).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("SETTLE_HOLD_REASON_REQUIRED"));
            hold(settlementId, true, "미처리 반품 확인").andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.settlement.status").value("HELD"))
                    .andExpect(jsonPath("$.data.settlement.holdReason").value("미처리 반품 확인"));
            pay(settlementId).andExpect(jsonPath("$.data.failed[0].code").value("SETTLE_INVALID_STATUS"));

            hold(settlementId, false, null).andExpect(jsonPath("$.data.settlement.status").value("SCHEDULED"));
            pay(settlementId).andExpect(jsonPath("$.data.success[0]").value(settlementId));
        }

        @Test
        @DisplayName("지급 — PAID · 지급 기록(계좌 스냅샷) · 아웃박스에 남긴 SettlementPaid 를 커밋 후 발행(SENT), 다시 지급하면 실패 목록 (이중 지급 없음)")
        void pay_success_publishesSettlementPaid() throws Exception {
            pay(settlementId).andExpect(status().isOk()).andExpect(jsonPath("$.data.success[0]").value(settlementId));
            pay(settlementId).andExpect(jsonPath("$.data.failed[0].code").value("SETTLE_INVALID_STATUS"));

            assertThat(settlementRepository.findById(settlementId).orElseThrow().getStatus()).isEqualTo(SettlementStatus.PAID);
            assertThat(payoutRepository.findBySettlementId(settlementId).orElseThrow().getBankName()).isEqualTo("기업은행");
            ArgumentCaptor<String> envelope = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher).send(eq("SettlementPaid"), eq(String.valueOf(settlementId)), anyString(), envelope.capture());
            JsonNode payload = jsonMapper.readTree(envelope.getValue()).path("payload");
            assertThat(payload.path("week").asString()).isEqualTo(W43);
            assertThat(payload.path("sellerEmail").asString()).isEqualTo("s" + storeId + "@example.com");
            assertThat(payload.path("payoutAmt").asLong()).isEqualTo(59_000L - 6_490L - 1_298L);
            assertThat(outboxOf(settlementId)).singleElement().satisfies(o -> assertThat(o.isSent()).isTrue());
        }

        @Test
        @DisplayName("SNS 가 죽어도 지급은 성공 — 이벤트는 아웃박스에 PENDING 으로 남고, 발행기가 같은 eventId 로 다시 보낸다")
        void pay_snsDown_outboxRetriesWithSameEventId() throws Exception {
            doThrow(SdkClientException.create("SNS 연결 실패")).doNothing()
                    .when(snsEventPublisher).send(eq("SettlementPaid"), eq(String.valueOf(settlementId)), anyString(), anyString());

            pay(settlementId).andExpect(status().isOk()).andExpect(jsonPath("$.data.success[0]").value(settlementId));
            assertThat(settlementRepository.findById(settlementId).orElseThrow().getStatus()).isEqualTo(SettlementStatus.PAID);
            EventOutbox pending = outboxOf(settlementId).getFirst();
            assertThat(pending.isSent()).isFalse();
            assertThat(pending.getAttemptCount()).isEqualTo(1);
            assertThat(pending.getLastError()).contains("SNS 연결 실패");

            eventOutboxService.publishPending();

            ArgumentCaptor<String> eventIds = ArgumentCaptor.forClass(String.class);
            verify(snsEventPublisher, times(2)).send(eq("SettlementPaid"), eq(String.valueOf(settlementId)), eventIds.capture(), anyString());
            assertThat(eventIds.getAllValues()).containsOnly(pending.getEventId());   // 소비자가 중복을 거를 수 있게 같은 eventId
            assertThat(outboxOf(settlementId)).singleElement().satisfies(o -> assertThat(o.isSent()).isTrue());
        }

        @Test
        @DisplayName("일괄 지급 — 계좌 없는 스토어 · 없는 정산서는 실패 목록, 나머지는 지급")
        void pay_batch_partialFailure() throws Exception {
            long noAccount = store(false);
            item(noAccount, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            long noAccountSettlement = settlementOf(noAccount).getId();

            pay(settlementId, noAccountSettlement, 999_999L).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.success.length()").value(1))
                    .andExpect(jsonPath("$.data.failed[0].code").value("SETTLE_PAYOUT_ACCOUNT_MISSING"))
                    .andExpect(jsonPath("$.data.failed[1].code").value("SETTLE_NOT_FOUND"));
            verify(snsEventPublisher, never()).send(eq("SettlementPaid"), eq(String.valueOf(noAccountSettlement)), any(), any());
            assertThat(outboxOf(noAccountSettlement)).isEmpty();   // 지급이 롤백되면 이벤트도 남지 않음
        }

        @Test
        @DisplayName("스토어 정지 → 지급예정 자동 보류 · 운영 재개 → 자동 보류만 자동 해제 (관리자 보류는 유지)")
        void storeStatus_autoHoldAndRelease() throws Exception {
            long other = store(true);
            item(other, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            long adminHeld = settlementOf(other).getId();
            hold(adminHeld, true, "관리자 확인 중");

            storeStatus(storeId, "SUSPENDED", "2026-10-26T09:00:00+09:00");
            storeStatus(other, "SUSPENDED", "2026-10-26T09:00:00+09:00");
            assertThat(settlementRepository.findById(settlementId).orElseThrow().getStatus()).isEqualTo(SettlementStatus.HELD);

            storeStatus(storeId, "ACTIVE", "2026-10-26T09:30:00+09:00");
            storeStatus(other, "ACTIVE", "2026-10-26T09:30:00+09:00");
            assertThat(settlementRepository.findById(settlementId).orElseThrow().getStatus()).isEqualTo(SettlementStatus.SCHEDULED);
            assertThat(settlementRepository.findById(adminHeld).orElseThrow().getStatus()).isEqualTo(SettlementStatus.HELD);
        }

        @Test
        @DisplayName("스토어 폐점(CLOSED) → 지급예정 자동 보류, 사유는 '스토어 폐점 — 자동 보류' · 지급 거절 (폐점 요청 10/21 답변 6번)")
        void storeClosed_autoHoldWithClosedReason() throws Exception {
            storeStatus(storeId, "CLOSED", "2026-10-26T09:00:00+09:00");

            assertThat(settlementRepository.findById(settlementId).orElseThrow().getStatus()).isEqualTo(SettlementStatus.HELD);
            assertThat(settlementHistoryRepository.findFirstBySettlementIdOrderByIdDesc(settlementId).orElseThrow().getReason())
                    .isEqualTo("스토어 폐점 — 자동 보류");
            pay(settlementId).andExpect(jsonPath("$.data.failed[0].code").value("SETTLE_INVALID_STATUS"));
        }
    }

    // ── 조회

    @Nested
    class Query {

        @Test
        @DisplayName("관리자 주차 목록 — 합계 행 · 스토어명 · 지급 계좌, 상세 — 건별 수수료율")
        void adminListAndDetail() throws Exception {
            long storeId = store(true);
            item(storeId, 59_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            long id = settlementOf(storeId).getId();

            mockMvc.perform(get("/internal/settlements").param("week", W43).param("storeId", String.valueOf(storeId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.week").value(W43))
                    .andExpect(jsonPath("$.data.totals.count").value(1))
                    .andExpect(jsonPath("$.data.totals.payoutAmt").value(51_212))
                    .andExpect(jsonPath("$.data.settlements[0].storeName").value("스토어" + storeId))
                    .andExpect(jsonPath("$.data.settlements[0].bank.bankName").value("기업은행"));
            mockMvc.perform(get("/internal/settlements/" + id))
                    .andExpect(jsonPath("$.data.items[0].productName").value("린넨 셔츠"))
                    .andExpect(jsonPath("$.data.items[0].rate").value(11.0))
                    .andExpect(jsonPath("$.data.items[0].commission").value(6_490));
        }

        @Test
        @DisplayName("주차 형식이 틀리면 400 · 없는 정산서 404 · 없는 경로 404")
        void invalidInputs() throws Exception {
            mockMvc.perform(get("/internal/settlements").param("week", "2026-43")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
            mockMvc.perform(get("/internal/settlements/999999")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("SETTLE_NOT_FOUND"));
            mockMvc.perform(get("/internal/no-such-path")).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }

        @Test
        @DisplayName("판매자 — 내 스토어 목록 · 요약(다음 지급 · 이번 주 예정 · 적용 수수료율), 다른 스토어 상세 403, 헤더 없음 401")
        void seller_ownershipAndSummary() throws Exception {
            long mine = store(true);
            long others = store(true);
            item(mine, 59_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            item(others, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            item(mine, 10_000L, LocalDateTime.of(2026, 10, 26, 9, 0));   // 이번 주 — 집계 전
            long othersSettlement = settlementOf(others).getId();

            asSeller(get("/api/seller/settlements"), mine).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.settlements.length()").value(1))
                    .andExpect(jsonPath("$.data.summary.nextPayout.payoutAmt").value(51_212))
                    .andExpect(jsonPath("$.data.summary.nextPayout.scheduledPayDate").value("2026-10-29"))
                    .andExpect(jsonPath("$.data.summary.pendingPayoutAmt").value(10_000 - 1_100 - 220))
                    .andExpect(jsonPath("$.data.summary.appliedRates[0].rate").value(11.0));
            asSeller(get("/api/seller/settlements/" + othersSettlement), mine).andExpect(status().isForbidden());
            mockMvc.perform(get("/api/seller/settlements")).andExpect(status().isUnauthorized());
        }

        @Test
        @DisplayName("판매자 명세서 — 지급 전 409, 지급 후에도 CSV 가 없으면 409, 생기면 10분짜리 presigned 링크, S3 장애는 502")
        @SuppressWarnings("unchecked")
        void seller_report() throws Exception {
            long storeId = store(true);
            item(storeId, 59_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();
            long id = settlementOf(storeId).getId();
            asSeller(get("/api/seller/settlements/" + id + "/report"), storeId).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SETTLE_REPORT_NOT_READY"));

            PresignedGetObjectRequest presigned = mock(PresignedGetObjectRequest.class);
            when(presigned.url()).thenReturn(URI.create("https://bucket.s3/reports/" + storeId + "/" + W43 + ".csv?sig").toURL());
            when(s3Presigner.presignGetObject(any(Consumer.class))).thenReturn(presigned);
            pay(id);

            // Lambda 가 아직 CSV 를 만들지 않음 — S3 오류 화면 대신 409
            when(s3Client.headObject(any(Consumer.class))).thenThrow(NoSuchKeyException.builder().statusCode(404).build());
            asSeller(get("/api/seller/settlements/" + id + "/report"), storeId).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SETTLE_REPORT_NOT_READY"))
                    .andExpect(jsonPath("$.message").value("명세서를 만들고 있습니다. 잠시 후 다시 시도해 주세요."));

            // HeadObject 404 는 본문이 없어 일반 S3Exception 으로 올 때도 같은 409
            reset(s3Client);
            when(s3Client.headObject(any(Consumer.class))).thenThrow(S3Exception.builder().statusCode(404).build());
            asSeller(get("/api/seller/settlements/" + id + "/report"), storeId).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("SETTLE_REPORT_NOT_READY"));

            // 권한 · 장애 — 502
            reset(s3Client);
            when(s3Client.headObject(any(Consumer.class))).thenThrow(S3Exception.builder().statusCode(403).build());
            asSeller(get("/api/seller/settlements/" + id + "/report"), storeId).andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("EXTERNAL_API_ERROR"));

            // 생성됨 — IRSA 임시 자격 증명 수명 안쪽인 10분 링크
            reset(s3Client);
            when(s3Client.headObject(any(Consumer.class))).thenReturn(HeadObjectResponse.builder().build());
            asSeller(get("/api/seller/settlements/" + id + "/report"), storeId).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.url").value("https://bucket.s3/reports/" + storeId + "/" + W43 + ".csv?sig"))
                    .andExpect(jsonPath("$.data.expiresAt").value("2026-10-26T10:10:00+09:00"));
        }

        @Test
        @DisplayName("미정산 현황 — 집계 전 · 지급예정 · 보류가 남아 있으면 clear=false, 전부 지급되면 clear=true (결정 3번 A안 폐점 · 탈퇴 조건)")
        void outstanding_clearOnlyWhenAllPaid() throws Exception {
            long storeId = store(true);
            item(storeId, 10_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            String url = "/internal/settlements/outstanding?storeId=" + storeId;

            mockMvc.perform(get(url)).andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.unsettledItemCount").value(1))
                    .andExpect(jsonPath("$.data.unsettledPayoutAmt").value(10_000 - 1_100 - 220))
                    .andExpect(jsonPath("$.data.clear").value(false));

            aggregate();
            long id = settlementOf(storeId).getId();
            hold(id, true, "확인 필요");
            mockMvc.perform(get(url))
                    .andExpect(jsonPath("$.data.unsettledItemCount").value(0))
                    .andExpect(jsonPath("$.data.heldCount").value(1))
                    .andExpect(jsonPath("$.data.clear").value(false));

            hold(id, false, null);
            mockMvc.perform(get(url)).andExpect(jsonPath("$.data.scheduledCount").value(1)).andExpect(jsonPath("$.data.clear").value(false));

            pay(id);
            mockMvc.perform(get(url))
                    .andExpect(jsonPath("$.data.scheduledCount").value(0))
                    .andExpect(jsonPath("$.data.heldCount").value(0))
                    .andExpect(jsonPath("$.data.clear").value(true));
        }

        @Test
        @DisplayName("대시보드 통계 — storeId 있으면 스토어(다음 지급), 없으면 플랫폼(지급 예정 · 보류)")
        void stats() throws Exception {
            long storeId = store(true);
            item(storeId, 59_000L, LocalDateTime.of(2026, 10, 20, 12, 0));
            aggregate();

            mockMvc.perform(get("/internal/stats/settlements").param("storeId", String.valueOf(storeId)))
                    .andExpect(jsonPath("$.data.store.nextPayout.payoutAmt").value(51_212))
                    .andExpect(jsonPath("$.data.platform").doesNotExist());
            mockMvc.perform(get("/internal/stats/settlements"))
                    .andExpect(jsonPath("$.data.platform.scheduledCount").isNumber())
                    .andExpect(jsonPath("$.data.store").doesNotExist());
        }
    }

    // ── 수수료

    @Nested
    class Commission {

        @Test
        @DisplayName("수수료 예약 — 날짜 생략 = 다음 주 월요일(11/02) 예약분, 오늘부터 적용분은 현재 율, 같은 날짜 재요청은 율만 수정")
        void commission_reserveAndList() throws Exception {
            long category = SEQ.incrementAndGet();
            String today = "[{\"categoryId\":%d,\"rate\":11.0,\"effectiveFrom\":\"2026-10-26\"}]".formatted(category);
            String reserve = "[{\"categoryId\":%d,\"rate\":11.5}]".formatted(category);
            String reserveAgain = "[{\"categoryId\":%d,\"rate\":12.0,\"effectiveFrom\":\"2026-11-02\"}]".formatted(category);

            mockMvc.perform(put("/internal/commissions").contentType(MediaType.APPLICATION_JSON).content(today)).andExpect(status().isOk());
            mockMvc.perform(put("/internal/commissions").contentType(MediaType.APPLICATION_JSON).content(reserve)).andExpect(status().isOk());
            mockMvc.perform(put("/internal/commissions").contentType(MediaType.APPLICATION_JSON).content(reserveAgain))
                    .andExpect(status().isOk());

            String json = mockMvc.perform(get("/internal/commissions")).andReturn().getResponse().getContentAsString();
            assertThat(json).contains("{\"categoryId\":" + category + ",\"current\":{\"rate\":11.00,\"effectiveFrom\":\"2026-10-26\"},"
                    + "\"reserved\":[{\"rate\":12.00,\"effectiveFrom\":\"2026-11-02\"}]}");
        }

        @Test
        @DisplayName("지난 날짜 400 COMMISSION_PAST_EFFECTIVE_DATE · 율 100 초과 400")
        void commission_invalid() throws Exception {
            mockMvc.perform(put("/internal/commissions").contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"categoryId\":1,\"rate\":11.0,\"effectiveFrom\":\"2026-10-25\"}]"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMISSION_PAST_EFFECTIVE_DATE"));
            mockMvc.perform(put("/internal/commissions").contentType(MediaType.APPLICATION_JSON)
                            .content("[{\"categoryId\":1,\"rate\":150.0}]"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        }
    }
}
