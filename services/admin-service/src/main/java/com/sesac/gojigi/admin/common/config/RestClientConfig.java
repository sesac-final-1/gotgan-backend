package com.sesac.gojigi.admin.common.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 모든 동기 호출의 공통 타임아웃 — 연결 2초 · 읽기 3초 (컨벤션 2-7). 값은 application.yml 의 app.client.* 에서 읽는다.
 * 대상별 클라이언트는 {domain}/client 에서 이 빌더로 @HttpExchange 프록시를 만들고, Resilience4j 는 ClientAdapter 에서 건다.
 */
@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder(
            @Value("${app.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.client.read-timeout:3s}") Duration readTimeout) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory);
    }
}
