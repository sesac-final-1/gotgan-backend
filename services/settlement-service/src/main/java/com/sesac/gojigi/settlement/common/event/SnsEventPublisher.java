package com.sesac.gojigi.settlement.common.event;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 봉투를 SNS 토픽에 발행 (docs/events.md 봉투 형식, raw delivery 라 구독 큐에는 봉투 JSON 그대로).
 * 커밋 이후에만 불러야 한다 — 호출하는 쪽은 @TransactionalEventListener(AFTER_COMMIT) (가이드라인 10장 1단계).
 * 토픽 ARN 이 비어 있으면(로컬 · 테스트) 발행하지 않고 로그만.
 */
@Slf4j
@Component
public class SnsEventPublisher {

    private final SnsClient snsClient;
    private final JsonMapper jsonMapper;
    private final String topicArn;

    public SnsEventPublisher(SnsClient snsClient, JsonMapper jsonMapper, @Value("${app.events.topic-arn:}") String topicArn) {
        this.snsClient = snsClient;
        this.jsonMapper = jsonMapper;
        this.topicArn = topicArn;
    }

    public <T> EventEnvelope<T> publish(String eventType, String aggregateId, T payload) {
        EventEnvelope<T> envelope = new EventEnvelope<>(UUID.randomUUID().toString(), eventType,
                OffsetDateTime.now(ZoneId.of("Asia/Seoul")), aggregateId, 1, payload);
        if (topicArn.isBlank()) {
            log.info("토픽 미설정 — 발행 생략 eventType={} aggregateId={}", eventType, aggregateId);
            return envelope;
        }
        snsClient.publish(b -> b.topicArn(topicArn).message(jsonMapper.writeValueAsString(envelope)));
        log.info("이벤트 발행 eventType={} aggregateId={} eventId={}", eventType, aggregateId, envelope.eventId());
        return envelope;
    }
}
