package com.sesac.gojigi.chat.common.config;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

/**
 * 밖으로 나가는 호출 — Keycloak 공개키(STOMP CONNECT 토큰 검증) · admin 알림 센터 · user · store(상대 이름 · 스토어 상태) · product(상품 카드) · settlement(정산 카드).
 * 타임아웃은 컨벤션 2-7(연결 2초 · 읽기 3초)보다 짧게 — 알림 · 이름은 빠져도 되는 부가 정보라 오래 기다리지 않는다
 */
@Configuration
public class ClientConfig {

    /** 게이트웨이와 같은 검증 — 서명(JWKS, 내부 주소) + 만료 + iss(공개 주소). 공개키는 Nimbus 가 캐시 */
    @Bean
    public JwtDecoder jwtDecoder(@Value("${app.jwt.jwk-set-uri}") String jwkSetUri, @Value("${app.jwt.issuer}") String issuer) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return decoder;
    }

    @Bean
    public RestClient adminRestClient(@Value("${app.admin-uri}") String adminUri) {
        return shortTimeout(adminUri);
    }

    @Bean
    public RestClient userRestClient(@Value("${app.user-uri}") String userUri) {
        return shortTimeout(userUri);
    }

    @Bean
    public RestClient storeRestClient(@Value("${app.store-uri}") String storeUri) {
        return shortTimeout(storeUri);
    }

    @Bean
    public RestClient productRestClient(@Value("${app.product-uri}") String productUri) {
        return shortTimeout(productUri);
    }

    @Bean
    public RestClient settlementRestClient(@Value("${app.settlement-uri}") String settlementUri) {
        return shortTimeout(settlementUri);
    }

    private static RestClient shortTimeout(String baseUrl) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(2));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** 알림 전송 전용 — 스레드 2개 · 대기 500건. 넘치면 버린다(ChatNotifier 가 기록) */
    @Bean
    public Executor chatNotifyExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("chat-notify-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.initialize();
        return executor;
    }
}
