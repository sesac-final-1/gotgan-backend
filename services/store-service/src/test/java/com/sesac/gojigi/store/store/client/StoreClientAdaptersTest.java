package com.sesac.gojigi.store.store.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.store.client.dto.SettlementOutstandingResponse;
import com.sesac.gojigi.store.store.exception.StoreErrorCode;
import com.sesac.gojigi.store.store.exception.StoreException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * order · settlement 어댑터 — RestClientConfig 와 같은 RestClient.builder() 로 실제 HTTP 응답을 파싱한다.
 * 응답은 상대 서비스가 실제로 내려 주는 전체 필드(store 가 안 읽는 필드 포함) — 모르는 필드가 무시되는지도 여기서 확인.
 * 서킷 · 재시도는 Spring AOP 라 여기서는 폴백 메서드를 직접 부른다.
 */
class StoreClientAdaptersTest {

    private MockRestServiceServer server;
    private OrderClientAdapter orderAdapter;
    private SettlementClientAdapter settlementAdapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        orderAdapter = new OrderClientAdapter(StoreClientConfig.create(builder, "http://order", OrderClient.class));
        settlementAdapter = new SettlementClientAdapter(StoreClientConfig.create(builder, "http://settlement", SettlementClient.class));
    }

    @Test
    @DisplayName("order 주문 통계 — 응답의 다른 필드(toConfirmCount 등)는 무시하고 inProgressCount 만 읽는다")
    void inProgressCount_fullOrderStats_readsInProgressOnly() {
        // given
        server.expect(requestTo("http://order/internal/stats/orders?storeId=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"주문 통계를 조회했습니다.","data":{"storeId":1,"toConfirmCount":2,"toShipCount":1,
                         "claimCount":0,"inProgressCount":3,"todaySalesAmt":52000}}""", MediaType.APPLICATION_JSON));

        // when · then
        assertThat(orderAdapter.inProgressCount(1L)).isEqualTo(3L);
        server.verify();
    }

    @Test
    @DisplayName("settlement 미정산 — 건수 필드는 무시하고 금액 세 개 · clear 를 읽는다, 합계는 세 금액의 합")
    void outstanding_fullResponse_readsAmountsAndClear() {
        // given
        server.expect(requestTo("http://settlement/internal/settlements/outstanding?storeId=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"미정산 현황을 조회했습니다.","data":{"storeId":1,"unsettledItemCount":2,
                         "unsettledPayoutAmt":30000,"scheduledCount":1,"scheduledPayoutAmt":48000,"heldCount":1,"heldPayoutAmt":12000,
                         "clear":false}}""", MediaType.APPLICATION_JSON));

        // when
        SettlementOutstandingResponse outstanding = settlementAdapter.outstanding(1L);

        // then
        assertThat(outstanding.clear()).isFalse();
        assertThat(outstanding.outstandingAmt()).isEqualTo(90_000L);
    }

    @Test
    @DisplayName("4xx 는 store 쪽 호출 버그 — 502 EXTERNAL_API_ERROR(BusinessException 이라 서킷 실패로 세지 않음)")
    void inProgressCount_whenClientError_throwsExternalApiError() {
        // given
        server.expect(requestTo("http://order/internal/stats/orders?storeId=1"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INVALID_INPUT\"}"));

        // when · then
        assertThatThrownBy(() -> orderAdapter.inProgressCount(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("폴백 — 연결 실패 · 서킷 OPEN 은 502 로 거절, 이미 변환된 업무 예외는 그대로")
    void fallbacks_mapFailuresTo502AndKeepBusinessErrors() {
        // given
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("order"));
        StoreException business = new StoreException(StoreErrorCode.STORE_NOT_FOUND);

        // when · then
        assertThatThrownBy(() -> orderAdapter.inProgressCountFallback(1L, new ResourceAccessException("timeout")))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> settlementAdapter.outstandingFallback(1L, open))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> orderAdapter.inProgressCountFallback(1L, business)).isSameAs(business);
    }
}
