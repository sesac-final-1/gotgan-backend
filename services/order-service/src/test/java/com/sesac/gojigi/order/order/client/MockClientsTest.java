package com.sesac.gojigi.order.order.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.order.order.client.dto.ShippingPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** store API 가 생기기 전 Mock 과 클라이언트 프록시 설정 */
class MockClientsTest {

    @Test
    @DisplayName("Mock 배송비 정책 — 3,000원, 50,000원 이상 무료")
    void mockShippingPolicy() {
        ShippingPolicy policy = new MockShippingPolicyClient().getShippingPolicy(1L);

        assertThat(policy.feeFor(49_999L)).isEqualTo(3_000L);
        assertThat(policy.feeFor(50_000L)).isZero();
    }

    @Test
    @DisplayName("user · payment · store · product 클라이언트 프록시를 만든다")
    void clientConfig_createsProxies() {
        OrderClientConfig config = new OrderClientConfig();
        RestClient.Builder builder = RestClient.builder();

        assertThat(config.userClient(builder, "http://user-service:8081")).isNotNull();
        assertThat(config.paymentClient(builder, "http://payment-service:8086")).isNotNull();
        assertThat(config.storeClient(builder, "http://store-service:8082")).isNotNull();
        assertThat(config.productClient(builder, "http://product-service:8083")).isNotNull();
    }
}
