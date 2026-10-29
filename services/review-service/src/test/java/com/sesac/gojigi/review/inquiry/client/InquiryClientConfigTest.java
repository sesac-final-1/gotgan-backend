package com.sesac.gojigi.review.inquiry.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.review.common.config.RestClientConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class InquiryClientConfigTest {

    @Test
    @DisplayName("공통 빌더(타임아웃)로 product · user 클라이언트 프록시를 만든다")
    void clients_createdFromSharedBuilder() {
        RestClient.Builder builder = new RestClientConfig().restClientBuilder(Duration.ofSeconds(2), Duration.ofSeconds(3));
        InquiryClientConfig config = new InquiryClientConfig();

        assertThat(config.productClient(builder, "http://product-service:8083")).isInstanceOf(ProductClient.class);
        assertThat(config.userClient(builder, "http://user-service:8081")).isInstanceOf(UserClient.class);
    }
}
