package com.sesac.gojigi.store.common.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sns.SnsClient;

/**
 * 봉투 JSON 을 SNS store-events 토픽에 발행 (docs/events.md 봉투 형식, raw delivery 라 구독 큐에는 봉투 JSON 그대로).
 * 구독 필터가 본문 eventType 을 보므로 MessageAttributes 는 달지 않는다. 직접 부르지 않고 EventOutboxService 를 거친다.
 * 토픽 ARN 이 비어 있으면(로컬 · 테스트) 발행하지 않고 로그만.
 */
@Slf4j
@Component
public class SnsEventPublisher {

    private final SnsClient snsClient;
    private final String topicArn;

    public SnsEventPublisher(SnsClient snsClient, @Value("${app.events.topic-arn:}") String topicArn) {
        this.snsClient = snsClient;
        this.topicArn = topicArn;
    }

    /** 실패하면 SDK 예외를 그대로 던진다 — 호출 쪽(아웃박스)이 실패로 기록하고 다시 보낸다 */
    public void send(String eventType, String aggregateId, String eventId, String envelopeJson) {
        if (topicArn.isBlank()) {
            log.info("토픽 미설정 — 발행 생략 eventType={} aggregateId={}", eventType, aggregateId);
            return;
        }
        snsClient.publish(b -> b.topicArn(topicArn).message(envelopeJson));
        log.info("이벤트 발행 eventType={} aggregateId={} eventId={}", eventType, aggregateId, eventId);
    }
}
