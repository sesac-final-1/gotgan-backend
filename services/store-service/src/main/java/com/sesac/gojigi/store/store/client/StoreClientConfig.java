package com.sesac.gojigi.store.store.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 다른 서비스 @HttpExchange 클라이언트 — 타임아웃은 RestClientConfig(공통) */
@Configuration
public class StoreClientConfig {

    @Bean
    public OrderClient orderClient(RestClient.Builder restClientBuilder, @Value("${app.client.order-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, OrderClient.class);
    }

    @Bean
    public SettlementClient settlementClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.settlement-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, SettlementClient.class);
    }

    static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient client = builder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }
}
