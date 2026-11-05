package com.sesac.gojigi.admin.common.client;

import com.sesac.gojigi.admin.category.client.ProductClient;
import com.sesac.gojigi.admin.dashboard.client.OrderClient;
import com.sesac.gojigi.admin.product.client.AdminProductClient;
import com.sesac.gojigi.admin.review.client.ReviewClient;
import com.sesac.gojigi.admin.settlement.client.SettlementBatchClient;
import com.sesac.gojigi.admin.settlement.client.SettlementClient;
import com.sesac.gojigi.admin.store.client.StoreClient;
import com.sesac.gojigi.admin.user.client.UserClient;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
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

    /** 집계 · 일괄 지급 전용 — 읽기 시간을 gateway · 프론트 제한(10초)보다 조금 짧게. 넘어도 서버 처리는 끝난다(집계는 멱등 · 지급은 정산서별 상태로 판단) */
    @Bean
    public SettlementBatchClient settlementBatchClient(RestClient.Builder restClientBuilder,
            @Value("${app.client.settlement-url}") String baseUrl,
            @Value("${app.client.connect-timeout:2s}") Duration connectTimeout,
            @Value("${app.client.settlement-batch-read-timeout:9s}") Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        return create(restClientBuilder.clone().requestFactory(factory), baseUrl, SettlementBatchClient.class);
    }

    @Bean
    public ProductClient productClient(RestClient.Builder restClientBuilder, @Value("${app.client.product-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, ProductClient.class);
    }

    @Bean
    public AdminProductClient adminProductClient(RestClient.Builder restClientBuilder, @Value("${app.client.product-url}") String baseUrl) {
        return create(restClientBuilder, baseUrl, AdminProductClient.class);
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
