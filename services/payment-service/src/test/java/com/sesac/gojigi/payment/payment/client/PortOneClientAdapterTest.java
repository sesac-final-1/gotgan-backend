package com.sesac.gojigi.payment.payment.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelRequest;
import com.sesac.gojigi.payment.payment.client.dto.PortOneCancelResponse;
import com.sesac.gojigi.payment.payment.client.dto.PortOnePayment;
import java.time.OffsetDateTime;
import java.util.Optional;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** 포트원 V2 응답 매핑 · 요청 모양 · 오류 변환 (서킷 브레이커 동작은 통합 테스트에서) */
class PortOneClientAdapterTest {

    private static final String BASE = "https://api.portone.io";

    private MockRestServiceServer server;
    private PortOneClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new PortOneClientAdapter(new PortOneClientConfig().portOneClient(builder, BASE, "test-secret"));
    }

    @Test
    @DisplayName("결제 조회 — 상태 · 금액 · 결제수단 · PG 거래 ID · 결제 시각을 읽고, 인증 헤더는 PortOne {시크릿}")
    void getPayment_mapsPaidPayment() {
        server.expect(requestTo(BASE + "/payments/pay-20261021-000123"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("Authorization", "PortOne test-secret"))
                .andRespond(withSuccess("""
                        {"status":"PAID","id":"pay-20261021-000123","transactionId":"tx-1","storeId":"store-1",
                         "amount":{"total":64400,"taxFree":0,"discount":0,"paid":64400,"cancelled":0,"cancelledTaxFree":0},
                         "method":{"type":"PaymentMethodEasyPay","provider":"TOSSPAY"},
                         "pgTxId":"pg-123","paidAt":"2026-10-21T00:12:33.123Z","unknownField":true}
                        """, MediaType.APPLICATION_JSON));

        PortOnePayment payment = adapter.getPayment("pay-20261021-000123").orElseThrow();

        assertThat(payment.isPaid()).isTrue();
        assertThat(payment.totalAmount()).isEqualTo(64_400L);
        assertThat(payment.methodCode()).isEqualTo("EASY_PAY");
        assertThat(payment.pgTxId()).isEqualTo("pg-123");
        assertThat(payment.paidAt()).isEqualTo(OffsetDateTime.parse("2026-10-21T00:12:33.123Z"));
        server.verify();
    }

    @Test
    @DisplayName("결제창을 연 적 없는 paymentId(포트원 404) 는 empty")
    void getPayment_notFound_returnsEmpty() {
        server.expect(requestTo(BASE + "/payments/pay-none"))
                .andRespond(withStatus(HttpStatus.NOT_FOUND).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"PAYMENT_NOT_FOUND\",\"message\":\"결제 건이 존재하지 않습니다.\"}"));

        assertThat(adapter.getPayment("pay-none")).isEqualTo(Optional.empty());
    }

    @Test
    @DisplayName("부분 취소 요청은 amount · reason · currentCancellableAmount 를 보내고 취소 ID 를 읽는다")
    void cancel_sendsPartialCancelBody() {
        server.expect(requestTo(BASE + "/payments/pay-1/cancel"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().json("{\"amount\":32200,\"reason\":\"구매자 취소\",\"currentCancellableAmount\":64400}", true))
                .andRespond(withSuccess("""
                        {"cancellation":{"status":"SUCCEEDED","id":"cancel-1","totalAmount":32200,"taxFreeAmount":0,
                         "vatAmount":0,"reason":"구매자 취소","cancelledAt":"2026-10-22T01:00:00Z","requestedAt":"2026-10-22T01:00:00Z"}}
                        """, MediaType.APPLICATION_JSON));

        PortOneCancelResponse response = adapter.cancel("pay-1", PortOneCancelRequest.of(32_200L, "구매자 취소", 64_400L));

        assertThat(response.cancellation().id()).isEqualTo("cancel-1");
        assertThat(response.cancellation().totalAmount()).isEqualTo(32_200L);
    }

    @Test
    @DisplayName("전액 취소도 amount 를 명시한다 — 선기록 금액과 포트원 처리 금액을 대조하려고")
    void cancel_fullCancelSendsAmount() {
        server.expect(requestTo(BASE + "/payments/pay-1/cancel"))
                .andExpect(content().json("{\"amount\":64400,\"reason\":\"주문 처리 실패\",\"currentCancellableAmount\":64400}", true))
                .andRespond(withSuccess("{\"cancellation\":{\"status\":\"SUCCEEDED\",\"id\":\"c\",\"totalAmount\":64400}}",
                        MediaType.APPLICATION_JSON));

        adapter.cancel("pay-1", PortOneCancelRequest.of(64_400L, "주문 처리 실패", 64_400L));
        server.verify();
    }

    @Test
    @DisplayName("포트원 4xx 거절은 EXTERNAL_API_ERROR + 포트원 오류 type 으로 바꾼다 — 서킷 실패로 세지 않음")
    void cancel_clientError_becomesRejectedWithType() {
        server.expect(requestTo(BASE + "/payments/pay-1/cancel"))
                .andRespond(withStatus(HttpStatus.CONFLICT).contentType(MediaType.APPLICATION_JSON)
                        .body("{\"type\":\"CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN\"}"));

        assertThatThrownBy(() -> adapter.cancel("pay-1", PortOneCancelRequest.of(1L, "x", 1L)))
                .isInstanceOfSatisfying(PortOneRejectedException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
                    assertThat(e.getPortoneType()).isEqualTo("CANCELLABLE_AMOUNT_CONSISTENCY_BROKEN");
                    assertThat(e.maybeAlreadyDone()).isTrue();
                });
    }

    @Test
    @DisplayName("오류 본문에서 포트원 type 을 꺼낸다 — 없거나 형식이 다르면 null")
    void errorType() {
        assertThat(PortOneClientAdapter.errorType("{\"type\" : \"PAYMENT_ALREADY_CANCELLED\",\"message\":\"x\"}"))
                .isEqualTo("PAYMENT_ALREADY_CANCELLED");
        assertThat(PortOneClientAdapter.errorType("<html>bad gateway</html>")).isNull();
        assertThat(PortOneClientAdapter.errorType(null)).isNull();
    }

    @Test
    @DisplayName("장애 폴백 — 서킷 OPEN · 연결 실패 모두 EXTERNAL_API_ERROR, 이미 변환된 4xx 거절은 그대로")
    void unavailable_mapsToExternalApiError() {
        CircuitBreaker open = CircuitBreaker.ofDefaults("portone");
        open.transitionToOpenState();

        assertThat(adapter.unavailable("조회", "pay-1", CallNotPermittedException.createCallNotPermittedException(open)).getErrorCode())
                .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThat(adapter.unavailable("취소", "pay-1", new ResourceAccessException("Read timed out")).getErrorCode())
                .isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        PortOneRejectedException rejected = new PortOneRejectedException("거절", "INVALID_REQUEST");
        assertThat(adapter.unavailable("취소", "pay-1", rejected)).isSameAs(rejected);
    }
}
