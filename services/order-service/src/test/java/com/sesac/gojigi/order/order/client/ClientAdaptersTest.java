package com.sesac.gojigi.order.order.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import com.sesac.gojigi.common.web.dto.ApiResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.common.web.exception.ErrorCode;
import com.sesac.gojigi.order.order.client.dto.PaymentCancelRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentPrepareRequest;
import com.sesac.gojigi.order.order.client.dto.PaymentResponse;
import com.sesac.gojigi.order.order.client.dto.PaymentVerifyRequest;
import com.sesac.gojigi.order.order.client.dto.PointRestoreResponse;
import com.sesac.gojigi.order.order.client.dto.PointReversalRequest;
import com.sesac.gojigi.order.order.client.dto.PointReversalResponse;
import com.sesac.gojigi.order.order.client.dto.StockRestoreRequest;
import com.sesac.gojigi.order.order.client.dto.PointUseRequest;
import com.sesac.gojigi.order.order.client.dto.StockItem;
import com.sesac.gojigi.order.order.client.dto.StockReleaseRequest;
import com.sesac.gojigi.order.order.client.dto.StockReserveRequest;
import com.sesac.gojigi.order.order.client.dto.StoreResponse;
import com.sesac.gojigi.order.order.client.dto.UserSummaryResponse;
import com.sesac.gojigi.order.order.exception.OrderErrorCode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

/**
 * 다른 서비스 호출 어댑터 — 4xx 응답 봉투의 code 를 주문 예외로 바꾸는지, 폴백이 장애를 502 로 바꾸는지.
 * 서킷 · 재시도 애노테이션은 스프링 AOP 라 여기서는 메서드 본문과 폴백을 직접 부른다.
 */
@ExtendWith(MockitoExtension.class)
class ClientAdaptersTest {

    private static final String SAGA_ID = "saga-1";

    static HttpClientErrorException rejected(HttpStatus status, String code) {
        String body = "{\"code\":\"" + code + "\",\"message\":\"x\",\"data\":null}";
        return HttpClientErrorException.create(status, status.getReasonPhrase(), HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
    }

    static void assertCode(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(expected);
    }

    @Nested
    @DisplayName("user")
    class User {

        @Mock private UserClient client;

        private UserClientAdapter adapter() {
            return new UserClientAdapter(client);
        }

        @Test
        @DisplayName("회원 조회 — 성공은 data, 404 는 ORDER_BUYER_NOT_FOUND, 그 밖의 4xx 는 502")
        void getUser() {
            given(client.getUser(4L)).willReturn(ApiResponse.success("ok", new UserSummaryResponse(4L, "a@b.c", "김", null)));
            assertThat(adapter().getUser(4L).email()).isEqualTo("a@b.c");

            given(client.getUser(5L)).willThrow(rejected(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));
            assertCode(() -> adapter().getUser(5L), OrderErrorCode.ORDER_BUYER_NOT_FOUND);

            given(client.getUser(6L)).willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));
            assertCode(() -> adapter().getUser(6L), CommonErrorCode.EXTERNAL_API_ERROR);
        }

        @Test
        @DisplayName("포인트 사용 — 잔액 부족 409 · 없는 회원 404 · 그 밖 거절을 각각 변환")
        void usePoints() {
            PointUseRequest request = new PointUseRequest(SAGA_ID, 4L, "20261022-000123", 1_000L);
            given(client.usePoints(request)).willThrow(rejected(HttpStatus.CONFLICT, "POINT_INSUFFICIENT"))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "USER_NOT_FOUND"))
                    .willThrow(rejected(HttpStatus.CONFLICT, "POINT_USE_CONFLICT"))
                    .willReturn(ApiResponse.success("ok", null));

            assertCode(() -> adapter().usePoints(request), OrderErrorCode.ORDER_POINT_INSUFFICIENT);
            assertCode(() -> adapter().usePoints(request), OrderErrorCode.ORDER_BUYER_NOT_FOUND);
            assertCode(() -> adapter().usePoints(request), CommonErrorCode.EXTERNAL_API_ERROR);
            adapter().usePoints(request);
        }

        @Test
        @DisplayName("포인트 사용 취소 — 성공 · 거절은 502")
        void restorePoints() {
            given(client.restorePoints(SAGA_ID)).willReturn(ApiResponse.success("ok", new PointRestoreResponse(SAGA_ID, 0L, null)))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            adapter().restorePoints(SAGA_ID);
            assertCode(() -> adapter().restorePoints(SAGA_ID), CommonErrorCode.EXTERNAL_API_ERROR);
        }

        @Test
        @DisplayName("폴백 — 연결 실패 · 서킷 OPEN 은 502, 이미 변환된 예외는 그대로")
        void fallbacks() {
            UserClientAdapter adapter = adapter();
            assertCode(() -> adapter.getUserFallback(4L, new ResourceAccessException("timeout")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.usePointsFallback(null,
                    CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("user"))),
                    CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.restorePointsFallback(SAGA_ID,
                    new BusinessException(OrderErrorCode.ORDER_BUYER_NOT_FOUND)), OrderErrorCode.ORDER_BUYER_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("product")
    class Product {

        @Mock private ProductClient client;

        private final StockReserveRequest reserve = new StockReserveRequest(SAGA_ID, List.of(new StockItem(11L, 1)));

        private ProductClientAdapter adapter() {
            return new ProductClientAdapter(client);
        }

        @Test
        @DisplayName("재고 예약 — 재고 부족 · 구매 불가 · 없는 옵션 · 그 밖 거절을 각각 변환")
        void reserve() {
            given(client.reserve(reserve)).willThrow(rejected(HttpStatus.CONFLICT, "STOCK_INSUFFICIENT"))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PRODUCT_NOT_PURCHASABLE"))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "PRODUCT_OPTION_NOT_FOUND"))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"))
                    .willReturn(ApiResponse.success("ok", null));

            assertCode(() -> adapter().reserve(reserve), OrderErrorCode.ORDER_OUT_OF_STOCK);
            assertCode(() -> adapter().reserve(reserve), OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            assertCode(() -> adapter().reserve(reserve), OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            assertCode(() -> adapter().reserve(reserve), CommonErrorCode.EXTERNAL_API_ERROR);
            adapter().reserve(reserve);
        }

        @Test
        @DisplayName("옵션 조회 · 재고 해제 — 성공 · 거절은 502, 폴백은 502")
        void getOptionsAndRelease() {
            StockReleaseRequest release = new StockReleaseRequest(reserve.items());
            given(client.getOptions(List.of(11L))).willReturn(ApiResponse.success("ok", List.of()))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));
            given(client.release(SAGA_ID, release)).willReturn(ApiResponse.success("ok", null))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            assertThat(adapter().getOptions(List.of(11L))).isEmpty();
            assertCode(() -> adapter().getOptions(List.of(11L)), CommonErrorCode.EXTERNAL_API_ERROR);
            adapter().release(SAGA_ID, release);
            assertCode(() -> adapter().release(SAGA_ID, release), CommonErrorCode.EXTERNAL_API_ERROR);

            ProductClientAdapter adapter = adapter();
            RuntimeException down = new ResourceAccessException("down");
            assertCode(() -> adapter.getOptionsFallback(List.of(11L), down), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.reserveFallback(reserve, down), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.releaseFallback(SAGA_ID, release, down), CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    @Nested
    @DisplayName("payment · store")
    class PaymentAndStore {

        @Mock private PaymentClient paymentClient;
        @Mock private StoreClient storeClient;
        @Mock private ProductClient productClient;
        @Mock private UserClient userClient;

        @Test
        @DisplayName("결제 준비 · 취소 — 성공 · 거절(PAYMENT_CANCEL_PENDING 포함)은 502, 폴백은 502")
        void payment() {
            PaymentClientAdapter adapter = new PaymentClientAdapter(paymentClient);
            PaymentPrepareRequest prepare = new PaymentPrepareRequest(1L, "20261022-000123", 62_000L, SAGA_ID);
            PaymentCancelRequest cancel = PaymentCancelRequest.full(SAGA_ID, "주문 처리 실패");
            given(paymentClient.prepare(prepare)).willReturn(ApiResponse.success("ok",
                    new PaymentResponse("20261022-000123", "pay-1", "READY", 62_000L, 0L, null, null)))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_ORDER_CONFLICT"));
            given(paymentClient.cancel(anyString(), any())).willReturn(ApiResponse.success("ok", null))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_CANCEL_PENDING"));

            assertThat(adapter.prepare(prepare).portonePaymentId()).isEqualTo("pay-1");
            assertCode(() -> adapter.prepare(prepare), CommonErrorCode.EXTERNAL_API_ERROR);
            adapter.cancel("20261022-000123", cancel);
            assertCode(() -> adapter.cancel("20261022-000123", cancel), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.prepareFallback(prepare, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.cancelFallback("20261022-000123", cancel, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
        }

        @Test
        @DisplayName("재고 복원 · 포인트 환원 회수 (취소 Saga) — 성공, 거절 · 폴백은 502")
        void restoreAndReverse() {
            ProductClientAdapter productAdapter = new ProductClientAdapter(productClient);
            UserClientAdapter userAdapter = new UserClientAdapter(userClient);
            StockRestoreRequest restore = new StockRestoreRequest(789L, 11L, 2);
            PointReversalRequest reverse = new PointReversalRequest(789L, 456L, 4L, "20261023-000001", 2_000L, 300L);
            given(productClient.restore(restore)).willReturn(ApiResponse.success("ok", null))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "PRODUCT_OPTION_NOT_FOUND"));
            given(userClient.reversePoints(reverse)).willReturn(ApiResponse.success("ok",
                    new PointReversalResponse(789L, 456L, 2_000L, 300L, 0L, 2_700L)))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            productAdapter.restore(restore);
            assertCode(() -> productAdapter.restore(restore), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> productAdapter.restoreFallback(restore, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
            userAdapter.reversePoints(reverse);
            assertCode(() -> userAdapter.reversePoints(reverse), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> userAdapter.reversePointsFallback(reverse, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
        }

        @Test
        @DisplayName("결제 검증 — 미결제는 결제 대기, 금액 불일치 · 끝난 결제 · 결제 없음은 결제 실패, 그 밖 거절 · 장애는 502")
        void payment_verify() {
            PaymentClientAdapter adapter = new PaymentClientAdapter(paymentClient);
            PaymentVerifyRequest verify = new PaymentVerifyRequest(SAGA_ID, "20261022-000123", 62_000L);
            given(paymentClient.verify(verify)).willReturn(ApiResponse.success("ok",
                    new PaymentResponse("20261022-000123", "pay-1", "PAID", 62_000L, 0L, "CARD", null)))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_NOT_COMPLETED"))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_AMOUNT_MISMATCH"))
                    .willThrow(rejected(HttpStatus.CONFLICT, "PAYMENT_INVALID_STATUS"))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            assertThat(adapter.verify(verify).status()).isEqualTo("PAID");
            assertCode(() -> adapter.verify(verify), OrderErrorCode.ORDER_PAYMENT_NOT_COMPLETED);
            assertCode(() -> adapter.verify(verify), OrderErrorCode.ORDER_PAYMENT_FAILED);
            assertCode(() -> adapter.verify(verify), OrderErrorCode.ORDER_PAYMENT_FAILED);
            assertCode(() -> adapter.verify(verify), OrderErrorCode.ORDER_PAYMENT_FAILED);
            assertCode(() -> adapter.verify(verify), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.verifyFallback(verify, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
        }

        @Test
        @DisplayName("결제 조회 — 성공은 data, 결제 없음(404) · 그 밖 거절 · 장애는 예외 없이 null")
        void payment_find() {
            PaymentClientAdapter adapter = new PaymentClientAdapter(paymentClient);
            given(paymentClient.find("20261022-000123")).willReturn(ApiResponse.success("ok",
                    new PaymentResponse("20261022-000123", "pay-1", "PAID", 62_000L, 0L, "CARD", null)))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "PAYMENT_NOT_FOUND"))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            assertThat(adapter.find("20261022-000123").method()).isEqualTo("CARD");
            assertThat(adapter.find("20261022-000123")).isNull();
            assertCode(() -> adapter.find("20261022-000123"), CommonErrorCode.EXTERNAL_API_ERROR);   // 폴백이 null 로 바꾼다
            assertThat(adapter.findFallback("20261022-000123", new ResourceAccessException("down"))).isNull();
            assertThat(adapter.findFallback("20261022-000123",
                    CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("payment"))))
                    .isNull();
        }

        @Test
        @DisplayName("스토어 조회 — 성공 · 없는 스토어는 구매 불가 · 그 밖 거절과 폴백은 502")
        void store() {
            StoreClientAdapter adapter = new StoreClientAdapter(storeClient);
            given(storeClient.getStore(anyLong())).willReturn(ApiResponse.success("ok",
                    new StoreResponse(1L, 2L, "곳간상회", "ACTIVE", 3_000L, 50_000L)))
                    .willThrow(rejected(HttpStatus.NOT_FOUND, "STORE_NOT_FOUND"))
                    .willThrow(rejected(HttpStatus.BAD_REQUEST, "INVALID_INPUT"));

            assertThat(adapter.getStore(1L).storeName()).isEqualTo("곳간상회");
            assertCode(() -> adapter.getStore(9L), OrderErrorCode.ORDER_PRODUCT_NOT_PURCHASABLE);
            assertCode(() -> adapter.getStore(1L), CommonErrorCode.EXTERNAL_API_ERROR);
            assertCode(() -> adapter.getStoreFallback(1L, new ResourceAccessException("down")),
                    CommonErrorCode.EXTERNAL_API_ERROR);
        }
    }

    @Test
    @DisplayName("응답 봉투에 code 가 없으면 null")
    void errorCode_missing() {
        HttpClientErrorException e = HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                HttpHeaders.EMPTY, "not json".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        assertThat(RemoteCalls.errorCode(e)).isNull();
    }
}
