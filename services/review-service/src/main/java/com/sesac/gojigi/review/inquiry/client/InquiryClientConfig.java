package com.sesac.gojigi.review.inquiry.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 문의 등록 스냅샷용 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제해 주소만 붙인다 (내부 호출은 인증 헤더 없음) */
@Configuration
public class InquiryClientConfig {

    @Bean
    public ProductClient productClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.product-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, ProductClient.class);
    }

    @Bean
    public UserClient userClient(RestClient.Builder restClientBuilder, @Value("${app.client.user-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, UserClient.class);
    }

    private static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient client = builder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }
}
