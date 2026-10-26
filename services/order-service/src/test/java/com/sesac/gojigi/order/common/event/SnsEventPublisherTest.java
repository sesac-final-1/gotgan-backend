package com.sesac.gojigi.order.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.order.common.config.AwsConfig;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

class SnsEventPublisherTest {

    @Test
    @DisplayName("토픽이 있으면 봉투 JSON 을 그대로 발행")
    @SuppressWarnings("unchecked")
    void send_publishesEnvelope() {
        SnsClient sns = mock(SnsClient.class);

        new SnsEventPublisher(sns, "arn:aws:sns:ap-northeast-2:1:order-events")
                .send("OrderItemShipped", "20261026-000001-01", "evt-1", "{\"eventId\":\"evt-1\"}");

        ArgumentCaptor<Consumer<PublishRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        verify(sns).publish(request.capture());
        PublishRequest.Builder builder = PublishRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().message()).isEqualTo("{\"eventId\":\"evt-1\"}");
        assertThat(builder.build().topicArn()).endsWith("order-events");
    }

    @Test
    @DisplayName("토픽이 비면(로컬 · 테스트) 발행하지 않음")
    void send_noTopic_skips() {
        SnsClient sns = mock(SnsClient.class);

        new SnsEventPublisher(sns, "").send("OrderItemShipped", "agg", "evt-1", "{}");

        verifyNoInteractions(sns);
    }

    @Test
    @DisplayName("AwsConfig — 리전 SNS 클라이언트")
    void awsConfig_snsClient() {
        try (SnsClient client = new AwsConfig().snsClient("ap-northeast-2")) {
            assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("ap-northeast-2");
        }
    }
}
