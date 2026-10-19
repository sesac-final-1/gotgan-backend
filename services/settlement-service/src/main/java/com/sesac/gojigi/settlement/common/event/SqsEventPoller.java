package com.sesac.gojigi.settlement.common.event;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQS 구독 큐 롱폴링 → eventType 별 SqsEventListener 로 전달 (가이드라인 10장: Spring Cloud AWS 대신 AWS SDK v2 SqsClient 롱폴링).
 * 큐마다 가상 스레드 1개. 큐 URL 이 비어 있으면(로컬 · 테스트) 띄우지 않는다. 종료 시 진행 중인 메시지를 마치고 멈춘다.
 */
@Slf4j
@Component
public class SqsEventPoller implements SmartLifecycle {

    private final SqsClient sqsClient;
    private final JsonMapper jsonMapper;
    private final Map<String, SqsEventListener> listeners;
    private final List<String> queueUrls;
    private final int waitSeconds;
    private final int maxMessages;
    private volatile boolean running;
    private List<Thread> workers = List.of();

    public SqsEventPoller(SqsClient sqsClient, JsonMapper jsonMapper, List<SqsEventListener> listeners,
            @Value("${app.sqs.store-events-queue-url:}") String storeEventsQueueUrl,
            @Value("${app.sqs.order-events-queue-url:}") String orderEventsQueueUrl,
            @Value("${app.sqs.wait-seconds:20}") int waitSeconds,
            @Value("${app.sqs.max-messages:10}") int maxMessages) {
        this.sqsClient = sqsClient;
        this.jsonMapper = jsonMapper;
        this.listeners = listeners.stream().collect(Collectors.toMap(SqsEventListener::eventType, Function.identity()));
        this.queueUrls = List.of(storeEventsQueueUrl, orderEventsQueueUrl).stream().filter(u -> !u.isBlank()).toList();
        this.waitSeconds = waitSeconds;
        this.maxMessages = maxMessages;
    }

    @Override
    public void start() {
        running = true;
        workers = queueUrls.stream()
                .map(url -> Thread.ofVirtual().name("sqs-" + url.substring(url.lastIndexOf('/') + 1)).start(() -> poll(url)))
                .toList();
        log.info("SQS 폴러 시작 - 큐 {}개, 리스너 {}", queueUrls.size(), listeners.keySet());
    }

    @Override
    public void stop() {
        running = false;
        workers.forEach(Thread::interrupt);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    private void poll(String queueUrl) {
        ReceiveMessageRequest request = ReceiveMessageRequest.builder()
                .queueUrl(queueUrl).waitTimeSeconds(waitSeconds).maxNumberOfMessages(maxMessages).build();
        while (running) {
            try {
                sqsClient.receiveMessage(request).messages().forEach(m -> dispatch(queueUrl, m));
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                log.error("SQS 수신 실패 - queue={}, cause={}", queueUrl, e.toString());
                sleepQuietly();   // 자격 증명 · 네트워크 오류가 계속될 때 로그 폭주 방지
            }
        }
    }

    /** 한 메시지 처리 — 성공 · 모르는 type 이면 삭제, 실패면 남겨 재전달 */
    void dispatch(String queueUrl, Message message) {
        String eventType = null;
        try {
            eventType = jsonMapper.readTree(message.body()).path("eventType").asString(null);
            SqsEventListener listener = listeners.get(eventType);
            if (listener == null) {
                log.warn("처리할 리스너 없는 이벤트 삭제 - eventType={}, messageId={}", eventType, message.messageId());
            } else {
                listener.onMessage(message.body());
            }
            sqsClient.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("이벤트 처리 실패 — 재전달 대기 - eventType={}, messageId={}, cause={}", eventType, message.messageId(), e.toString());
        }
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
