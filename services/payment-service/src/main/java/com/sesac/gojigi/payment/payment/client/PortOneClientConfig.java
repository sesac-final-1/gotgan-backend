package com.sesac.gojigi.payment.payment.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 포트원 클라이언트 — 공통 RestClient.Builder(연결 2초 · 읽기 3초)를 복제해 주소 · 인증 헤더만 붙인다 */
@Configuration
public class PortOneClientConfig {

    @Bean
    public PortOneClient portOneClient(RestClient.Builder restClientBuilder,
            @Value("${app.portone.api-base-url}") String baseUrl,
            @Value("${app.portone.api-secret:}") String apiSecret) {
        RestClient client = restClientBuilder.clone()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "PortOne " + apiSecret)   // 시크릿은 환경 변수로만, 로그 금지
                .build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build()
                .createClient(PortOneClient.class);
    }
}
