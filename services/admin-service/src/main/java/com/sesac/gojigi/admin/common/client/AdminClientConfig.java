package com.sesac.gojigi.admin.common.client;

import com.sesac.gojigi.admin.category.client.ProductClient;
import com.sesac.gojigi.admin.dashboard.client.OrderClient;
import com.sesac.gojigi.admin.review.client.ReviewClient;
import com.sesac.gojigi.admin.settlement.client.SettlementClient;
import com.sesac.gojigi.admin.store.client.StoreClient;
import com.sesac.gojigi.admin.user.client.UserClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpServiceProxyFactory;

/** 다른 서비스 내부 API 클라이언트 — 공통 타임아웃 빌더(RestClientConfig)로 @HttpExchange 프록시를 만든다. 주소는 ConfigMap *_SERVICE_URI */
@Configuration
public class AdminClientConfig {

    @Bean
    public UserClient userClient(RestClient.Builder restClientBuilder, @Value("${app.client.user-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, UserClient.class);
    }

    @Bean
    public SettlementClient settlementClient(RestClient.Builder restClientBuilder, @Value("${app.client.settlement-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, SettlementClient.class);
    }

    @Bean
    public ProductClient productClient(RestClient.Builder restClientBuilder, @Value("${app.client.product-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, ProductClient.class);
    }

    @Bean
    public OrderClient orderClient(RestClient.Builder restClientBuilder, @Value("${app.client.order-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, OrderClient.class);
    }

    @Bean
    public StoreClient storeClient(RestClient.Builder restClientBuilder, @Value("${app.client.store-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, StoreClient.class);
    }

    @Bean
    public ReviewClient reviewClient(RestClient.Builder restClientBuilder, @Value("${app.client.review-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, ReviewClient.class);
    }

    private static <T> T create(RestClient.Builder builder, String baseUrl, Class<T> type) {
        RestClient client = builder.clone().baseUrl(baseUrl).build();
        return HttpServiceProxyFactory.builderFor(RestClientAdapter.create(client)).build().createClient(type);
    }
}
