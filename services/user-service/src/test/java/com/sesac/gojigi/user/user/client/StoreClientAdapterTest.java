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

/** store 스토어 상세 응답 매핑 · 요청 경로 · 오류 변환 (서킷 브레이커 · 재시도는 AOP 라 여기서 보지 않음) */
class StoreClientAdapterTest {

    private static final String BASE_URL = "http://store-service:8082";

    private MockRestServiceServer server;
    private StoreClientAdapter adapter;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        adapter = new StoreClientAdapter(new StoreClientConfig().storeClient(builder, BASE_URL));
    }

    @Test
    @DisplayName("GET /internal/stores/{storeId} 의 status 를 읽는다 — 나머지 필드(스토어명 · 배송 정책)는 무시")
    void getStoreStatus_readsStatus() {
        server.expect(requestTo(BASE_URL + "/internal/stores/1")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        {"code":"SUCCESS","message":"스토어를 조회했습니다.","data":{"storeId":1,"userId":2,
                         "storeName":"곳간상회","status":"CLOSED","shippingFee":3000,"freeShippingThreshold":30000,
                         "courier":"우체국택배"}}""", MediaType.APPLICATION_JSON));

        assertThat(adapter.getStoreStatus(1L)).isEqualTo(StoreStatus.CLOSED);
        server.verify();
    }

    @Test
    @DisplayName("없는 스토어(404)는 폐점을 확인하지 못한 것 — 502 로 탈퇴를 막는다")
    void getStoreStatus_notFound_throwsExternalApiError() {
        server.expect(requestTo(BASE_URL + "/internal/stores/9")).andRespond(withStatus(HttpStatus.NOT_FOUND)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"STORE_NOT_FOUND\",\"message\":\"스토어를 찾을 수 없습니다.\"}"));

        assertThatThrownBy(() -> adapter.getStoreStatus(9L)).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
    }

    @Test
    @DisplayName("폴백 — 장애 · 서킷 OPEN 은 502, 이미 바꾼 4xx 는 그대로")
    void fallback() {
        BusinessException rejected = new BusinessException(CommonErrorCode.EXTERNAL_API_ERROR, "거절");

        assertThatThrownBy(() -> adapter.getStoreStatusFallback(1L, new ResourceAccessException("down")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(CommonErrorCode.EXTERNAL_API_ERROR);
        assertThatThrownBy(() -> adapter.getStoreStatusFallback(1L, rejected)).isSameAs(rejected);
    }
}
