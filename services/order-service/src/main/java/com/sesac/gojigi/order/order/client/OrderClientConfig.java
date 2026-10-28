package com.sesac.gojigi.order.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 다른 서비스 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제해 주소만 붙인다 (내부 API 는 인증 헤더 없음) */
@Configuration
public class OrderClientConfig {

    @Bean
    public UserClient userClient(RestClient.Builder restClientBuilder, @Value("${app.client.user-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, UserClient.class);
    }

    @Bean
    public PaymentClient paymentClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.payment-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, PaymentClient.class);
    }

    @Bean
    public StoreClient storeClient(RestClient.Builder restClientBuilder, @Value("${app.client.store-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, StoreClient.class);
    }

    @Bean
    public ProductClient productClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.product-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, ProductClient.class);
    }

    private static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient client = builder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }
}
