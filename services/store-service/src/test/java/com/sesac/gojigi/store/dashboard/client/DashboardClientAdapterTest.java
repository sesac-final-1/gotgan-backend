package com.sesac.gojigi.store.dashboard.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.store.store.client.StoreClientConfig;
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
import tools.jackson.databind.JsonNode;

/**
 * 대시보드 통계 어댑터 — RestClientConfig 와 같은 RestClient.builder() 로 실제 HTTP 응답을 파싱한다.
 * 서비스마다 base URL 이 다른지 · product · settlement 는 store 객체만 꺼내는지 확인. 서킷 · 재시도는 Spring AOP 라 폴백은 직접 부른다.
 */
class DashboardClientAdapterTest {

    private MockRestServiceServer server;
    private DashboardClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new DashboardClientAdapter(
                StoreClientConfig.create(builder, "http://order", StatsClient.class),
                StoreClientConfig.create(builder, "http://product", StatsClient.class),
                StoreClientConfig.create(builder, "http://settlement", StatsClient.class));
    }

    @Test
    @DisplayName("order 주문 통계 · 일별 통계 — order 주소로 storeId · days 를 보내고 data 를 그대로 돌려준다")
    void orderStats_andDaily_returnDataAsIs() {
        // given
        server.expect(requestTo("http://order/internal/stats/orders?storeId=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"스토어 주문 통계를 조회했습니다.","data":{"storeId":1,"toConfirmCount":2,
                         "toShipCount":1,"claimCount":0,"inProgressCount":3,"todaySalesAmt":52000}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://order/internal/stats/orders/daily?storeId=1&days=30")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"일별 주문 통계를 조회했습니다.","data":[{"date":"2026-10-29","tradeAmt":0,"orderCount":0},
                         {"date":"2026-10-30","tradeAmt":26980,"orderCount":1}]}""", MediaType.APPLICATION_JSON));

        // when
        JsonNode order = adapter.orderStats(1L);
        JsonNode daily = adapter.orderDailyStats(1L, 30);

        // then
        assertThat(order.get("toConfirmCount").asInt()).isEqualTo(2);
        assertThat(order.get("todaySalesAmt").asLong()).isEqualTo(52_000L);
        assertThat(daily.isArray()).isTrue();
        assertThat(daily.get(1).get("tradeAmt").asLong()).isEqualTo(26_980L);
        server.verify();
    }

    @Test
    @DisplayName("product · settlement 통계 — 각 서비스 주소로 부르고 응답의 store 객체만 꺼낸다")
    void productAndSettlementStats_returnStoreObject() {
        // given
        server.expect(requestTo("http://product/internal/stats/products?storeId=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"상품 통계를 조회했습니다.","data":{"store":{"onSaleCount":5,"lowStockCount":1,
                         "soldOutCount":0,"stoppedCount":2,"lowStockProducts":[{"productId":101,"name":"햇감자","stock":3,"imageUrl":null}]},
                         "platform":null}}""", MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://settlement/internal/stats/settlements?storeId=1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"정산 통계를 조회했습니다.","data":{"store":{"pendingItemCount":2,
                         "pendingPayoutAmt":48000,"nextPayout":null,"recentPaid":[]},"platform":null}}""", MediaType.APPLICATION_JSON));

        // when
        JsonNode product = adapter.productStats(1L);
        JsonNode settlement = adapter.settlementStats(1L);

        // then
        assertThat(product.get("onSaleCount").asInt()).isEqualTo(5);
        assertThat(product.get("lowStockProducts").get(0).get("productId").asLong()).isEqualTo(101L);
        assertThat(settlement.get("pendingPayoutAmt").asLong()).isEqualTo(48_000L);
        assertThat(settlement.has("platform")).isFalse();
        server.verify();
    }

    @Test
    @DisplayName("4xx 는 store 쪽 호출 버그 — 502 EXTERNAL_API_ERROR(BusinessException 이라 서킷 실패로 세지 않음)")
    void productStats_whenClientError_throwsExternalApiError() {
        // given
        server.expect(requestTo("http://product/internal/stats/products?storeId=1"))
                .andRespond(withBadRequest().contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"INVALID_INPUT\"}"));

        // when · then
        assertThatThrownBy(() -> adapter.productStats(1L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("폴백 — 연결 실패 · 서킷 OPEN 은 502, 이미 변환된 업무 예외는 그대로")
    void fallbacks_mapFailuresTo502AndKeepBusinessErrors() {
        // given
        CallNotPermittedException open = CallNotPermittedException.createCallNotPermittedException(CircuitBreaker.ofDefaults("settlement"));
        BusinessException business = new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR);
        ResourceAccessException timeout = new ResourceAccessException("timeout");

        // when · then
        assertThatThrownBy(() -> adapter.orderStatsFallback(1L, timeout))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.orderDailyStatsFallback(1L, 30, timeout))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.productStatsFallback(1L, timeout))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.settlementStatsFallback(1L, open))
                .extracting(e -> ((BusinessException) e).getErrorCode()).isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.orderStatsFallback(1L, business)).isSameAs(business);
    }
}
