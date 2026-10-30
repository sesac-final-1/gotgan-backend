package com.sesac.gojigi.store.dashboard.client;

import com.sesac.gojigi.store.store.client.StoreClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** 대시보드 통계 클라이언트 — 같은 StatsClient 를 서비스별 base URL 로 하나씩. 타임아웃은 RestClientConfig(공통) */
@Configuration
public class DashboardClientConfig {

    @Bean
    public StatsClient orderStatsClient(RestClient.Builder restClientBuilder, @Value("${app.client.order-url}") String baseUrl) {
        return StoreClientConfig.create(restClientBuilder, baseUrl, StatsClient.class);
    }

    @Bean
    public StatsClient productStatsClient(RestClient.Builder restClientBuilder, @Value("${app.client.product-url}") String baseUrl) {
        return StoreClientConfig.create(restClientBuilder, baseUrl, StatsClient.class);
    }

    @Bean
    public StatsClient settlementStatsClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.settlement-url}") String baseUrl) {
        return StoreClientConfig.create(restClientBuilder, baseUrl, StatsClient.class);
    }
}
