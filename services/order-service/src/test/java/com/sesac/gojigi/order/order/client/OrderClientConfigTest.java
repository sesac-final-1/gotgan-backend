package com.sesac.gojigi.order.order.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/** 다른 서비스 클라이언트 프록시 설정 */
class OrderClientConfigTest {

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
