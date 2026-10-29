package com.sesac.gojigi.user.user.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** store 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제해 주소만 붙인다 (내부 API 는 인증 헤더 없음) */
@Configuration
public class StoreClientConfig {

    @Bean
    public StoreClient storeClient(RestClient.Builder restClientBuilder, @Value("${app.client.store-url}") String baseUrl) {
        RestClient client = restClientBuilder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(StoreClient.class);
    }
}
