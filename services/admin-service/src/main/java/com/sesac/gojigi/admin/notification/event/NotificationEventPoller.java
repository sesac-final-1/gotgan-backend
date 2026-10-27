package com.sesac.gojigi.admin.notification.event;

import com.sesac.gojigi.admin.notification.service.NotificationService;
import java.util.List;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

/**
 * 알림 센터 구독 큐 3개(admin-store · admin-order · admin-settlement-events) 롱폴링 → NotificationService.
 * settlement SqsEventPoller 와 같은 방식(AWS SDK v2, 큐마다 가상 스레드 1개, URL 이 비면 띄우지 않음).
 * 처리 성공 · 알림을 만들지 않는 이벤트 = 삭제, 실패 = 남겨 재전달(3회 실패 시 DLQ · 알람). 같은 이벤트 재전달은 알림함 조건부 쓰기로 한 번만
 */
@Slf4j
@Component
public class NotificationEventPoller implements SmartLifecycle {

    /** 연속 수신 실패 요약 로그 간격 — 5초 × 12 = 1분 */
    static final int FAILURE_SUMMARY_EVERY = 12;

    private final SqsClient sqsClient;
    private final NotificationService notificationService;
    private final List<String> queueUrls;
    private volatile boolean running;
    private List<Thread> workers = List.of();

    public NotificationEventPoller(SqsClient sqsClient, NotificationService notificationService,
            @Value("${app.notification.queues.store:}") String storeQueue,
            @Value("${app.notification.queues.order:}") String orderQueue,
            @Value("${app.notification.queues.settlement:}") String settlementQueue) {
        this.sqsClient = sqsClient;
        this.notificationService = notificationService;
        this.queueUrls = Stream.of(storeQueue, orderQueue, settlementQueue).filter(u -> !u.isBlank()).toList();
    }

    @Override
    public void start() {
        running = true;
        workers = queueUrls.stream()
                .map(url -> Thread.ofVirtual().name("notify-" + url.substring(url.lastIndexOf('/') + 1)).start(() -> poll(url)))
                .toList();
        log.info("알림 이벤트 폴러 시작 - 큐 {}개", queueUrls.size());
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
        ReceiveMessageRequest request = ReceiveMessageRequest.builder().queueUrl(queueUrl).waitTimeSeconds(20).maxNumberOfMessages(10).build();
        int consecutiveFailures = 0;
        while (running) {
            try {
                sqsClient.receiveMessage(request).messages().forEach(m -> dispatch(queueUrl, m));
                consecutiveFailures = 0;
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                consecutiveFailures++;
                if (consecutiveFailures == 1) {
                    log.error("알림 이벤트 수신 실패 - queue={}", queueUrl, e);
                } else if (consecutiveFailures % FAILURE_SUMMARY_EVERY == 0) {
                    log.error("알림 이벤트 수신 연속 실패 {}회 - queue={}, cause={}", consecutiveFailures, queueUrl, e.toString());
                }
                sleepQuietly();   // 자격 증명 · 네트워크 오류가 계속될 때 로그 폭주 방지
            }
        }
    }

    /** 한 메시지 — 성공이면 삭제, 실패면 남겨 가시성 타임아웃(60초) 뒤 재전달 */
    void dispatch(String queueUrl, Message message) {
        try {
            notificationService.receiveEvent(message.body());
            sqsClient.deleteMessage(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException e) {
            log.warn("알림 이벤트 처리 실패 — 재전달 대기 messageId={}, cause={}", message.messageId(), e.toString());
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
