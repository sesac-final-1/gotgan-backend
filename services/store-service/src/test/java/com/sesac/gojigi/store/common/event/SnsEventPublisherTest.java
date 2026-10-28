package com.sesac.gojigi.store.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.sesac.gojigi.store.common.config.AwsConfig;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sns.model.PublishRequest;

class SnsEventPublisherTest {

    @Test
    @DisplayName("토픽이 있으면 봉투 JSON 을 그대로 발행한다")
    @SuppressWarnings("unchecked")
    void send_whenTopicSet_publishesEnvelope() {
        // given
        SnsClient sns = mock(SnsClient.class);

        // when
        new SnsEventPublisher(sns, "arn:aws:sns:ap-northeast-2:1:store-events")
                .send("StoreApproved", "3", "evt-1", "{\"eventId\":\"evt-1\"}");

        // then
        ArgumentCaptor<Consumer<PublishRequest.Builder>> request = ArgumentCaptor.forClass(Consumer.class);
        verify(sns).publish(request.capture());
        PublishRequest.Builder builder = PublishRequest.builder();
        request.getValue().accept(builder);
        assertThat(builder.build().message()).isEqualTo("{\"eventId\":\"evt-1\"}");
        assertThat(builder.build().topicArn()).endsWith("store-events");
    }

    @Test
    @DisplayName("토픽이 비면(로컬 · 테스트) 발행하지 않는다")
    void send_whenNoTopic_skips() {
        // given
        SnsClient sns = mock(SnsClient.class);

        // when
        new SnsEventPublisher(sns, "").send("StoreApproved", "3", "evt-1", "{}");

        // then
        verifyNoInteractions(sns);
    }

    @Test
    @DisplayName("AwsConfig 는 설정한 리전의 SNS 클라이언트를 호출 1초 · 시도 500ms 상한으로 만든다")
    void snsClient_whenRegionGiven_usesRegionAndShortTimeouts() {
        try (SnsClient client = new AwsConfig().snsClient("ap-northeast-2")) {
            assertThat(client.serviceClientConfiguration().region().id()).isEqualTo("ap-northeast-2");
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallTimeout())
                    .hasValue(java.time.Duration.ofSeconds(1));
            assertThat(client.serviceClientConfiguration().overrideConfiguration().apiCallAttemptTimeout())
                    .hasValue(java.time.Duration.ofMillis(500));
        }
    }
}
