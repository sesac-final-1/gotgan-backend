package com.sesac.gojigi.store.common.config;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * 공통 빌더가 나가는 요청을 관측하는지 — 끊기면 받는 서비스가 새 traceId 로 시작한다 (QA Q-박-5).
 */
class RestClientConfigTest {

    private static final String TRACEPARENT = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    @Test
    @DisplayName("공통 빌더로 만든 요청은 관측되고, 트레이싱 핸들러가 넣은 traceparent 헤더가 실려 나간다")
    void restClientBuilder_outgoingRequest_carriesTraceparent() {
        // given — 실제 트레이싱 핸들러처럼 요청 컨텍스트의 setter 로 헤더를 넣는다
        ObservationRegistry registry = ObservationRegistry.create();
        registry.observationConfig().observationHandler(new ObservationHandler<ClientRequestObservationContext>() {
            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ClientRequestObservationContext;
            }

            @Override
            public void onStart(ClientRequestObservationContext context) {
                context.getSetter().set(context.getCarrier(), "traceparent", TRACEPARENT);
            }
        });
        RestClient.Builder builder = new RestClientConfig()
                .restClientBuilder(Duration.ofSeconds(2), Duration.ofSeconds(3), registry);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://order-service:8084/internal/stats/orders"))
                .andExpect(header("traceparent", TRACEPARENT))
                .andRespond(withSuccess());

        // when
        builder.build().get().uri("http://order-service:8084/internal/stats/orders").retrieve().toBodilessEntity();

        // then
        server.verify();
    }
}
