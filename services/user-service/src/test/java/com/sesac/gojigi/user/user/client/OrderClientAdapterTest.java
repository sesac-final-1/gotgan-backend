package com.sesac.gojigi.user.user.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** order 구매자 주문 통계 응답 매핑 · 요청 경로 · 오류 변환 (서킷 브레이커 · 재시도는 AOP 라 여기서 보지 않음) */
class OrderClientAdapterTest {

    private static final String BASE_URL = "http://order-service:8084";

    private MockRestServiceServer server;
    private OrderClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new OrderClientAdapter(new OrderClientConfig().orderClient(builder, BASE_URL));
    }

    @Test
    @DisplayName("GET /internal/stats/orders?buyerId= 의 inProgressCount 를 읽는다")
    void countInProgress_readsCount() {
        server.expect(requestTo(BASE_URL + "/internal/stats/orders?buyerId=7")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"구매자 주문 통계를 조회했습니다.",
                         "data":{"buyerId":7,"inProgressCount":3,"openClaimCount":1}}""", MediaType.APPLICATION_JSON));

        assertThat(adapter.countInProgress(7L)).isEqualTo(3L);
        server.verify();
    }

    @Test
    @DisplayName("거절(4xx)은 주문을 확인하지 못한 것 — 502 로 탈퇴를 막는다")
    void countInProgress_rejected_throwsExternalApiError() {
        server.expect(requestTo(BASE_URL + "/internal/stats/orders?buyerId=9")).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"INVALID_INPUT\",\"message\":\"입력값이 올바르지 않습니다.\"}"));

        assertThatThrownBy(() -> adapter.countInProgress(9L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("폴백 — 장애 · 서킷 OPEN 은 502, 이미 바꾼 4xx 는 그대로")
    void fallback() {
        BusinessException rejected = new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "거절");

        assertThatThrownBy(() -> adapter.countInProgressFallback(1L, new ResourceAccessException("down")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.countInProgressFallback(1L, rejected)).isSameAs(rejected);
    }
}
