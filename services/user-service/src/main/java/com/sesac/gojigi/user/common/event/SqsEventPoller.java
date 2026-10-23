package com.sesac.gojigi.user.common.event;

import java.net.ConnectException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.CannotCreateTransactionException;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQS 구독 큐 롱폴링 → eventType 별 SqsEventListener 로 전달 (가이드라인 10장: Spring Cloud AWS 대신 AWS SDK v2 SqsClient 롱폴링).
 * 큐마다 가상 스레드 1개. 큐 URL 이 비어 있으면(로컬 · 테스트) 띄우지 않는다. 종료 시 진행 중인 메시지를 마치고 멈춘다.
 */
@Slf4j
@Component
public class SqsEventPoller implements SmartLifecycle {

    /** 연속 수신 실패 요약 로그 간격 — sleepQuietly 5초 × 12 = 1분 */
    static final int FAILURE_SUMMARY_EVERY = 12;

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
                .queueUrl(queueUrl).waitTimeSeconds(waitSeconds).maxNumberOfMessages(maxMessages)
                .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT)   // 일시 장애 재시도 간격 계산용
                .build();
        int consecutiveFailures = 0;
        while (running) {
            try {
                sqsClient.receiveMessage(request).messages().forEach(m -> dispatch(queueUrl, m));
                if (consecutiveFailures > 0) {
                    log.info("SQS 수신 복구 - queue={}, 연속 실패 {}회 후", queueUrl, consecutiveFailures);
                    consecutiveFailures = 0;
                }
            } catch (RuntimeException e) {
                if (!running) {
                    return;
                }
                consecutiveFailures++;
                logReceiveFailure(queueUrl, consecutiveFailures, e);
                sleepQuietly();   // 자격 증명 · 네트워크 오류가 계속될 때 로그 폭주 방지
            }
        }
    }

    /**
     * 수신 실패 — 사람이 봐야 하므로 error. 5초마다 반복되므로 연속 실패의 첫 회만 스택트레이스(컨벤션 2-8),
     * 이후는 1분(12회)마다 요약 한 줄 — 같은 스택이 수백 줄 쌓여 Loki 를 덮지 않게.
     */
    static boolean shouldLogFailure(int consecutiveFailures) {
        return consecutiveFailures == 1 || consecutiveFailures % FAILURE_SUMMARY_EVERY == 0;
    }

    private void logReceiveFailure(String queueUrl, int consecutiveFailures, RuntimeException e) {
        if (consecutiveFailures == 1) {
            log.error("SQS 수신 실패 - queue={}", queueUrl, e);
        } else if (shouldLogFailure(consecutiveFailures)) {
            log.error("SQS 수신 연속 실패 {}회 - queue={}, cause={}", consecutiveFailures, queueUrl, e.toString());
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
            if (isTransient(e)) {
                delayRetry(queueUrl, message, eventType, e);
                return;
            }
            log.warn("이벤트 처리 실패 — 재전달 대기 - eventType={}, messageId={}, cause={}", eventType, message.messageId(), e.toString());
        }
    }

    /**
     * DB 연결 끊김 같은 일시 장애 — 큐 기본 가시성(60초)대로 바로 다시 받으면 3회(maxReceiveCount)를 몇 분 만에 다 써서
     * 정상 메시지가 DLQ 로 몰린다. 받은 횟수에 따라 1분 → 5분 → 15분 뒤에 다시 보이게 해 짧은 장애는 저절로 넘긴다.
     * 업무 오류는 그대로 — 원인을 고치고 DLQ 에서 다시 보낸다 (settlement 와 같은 폴러).
     */
    private void delayRetry(String queueUrl, Message message, String eventType, RuntimeException cause) {
        int delay = retryDelaySeconds(receiveCount(message));
        log.warn("이벤트 처리 일시 실패 — {}초 뒤 재시도 eventType={}, messageId={}, receiveCount={}, cause={}",
                delay, eventType, message.messageId(), receiveCount(message), cause.toString());
        try {
            sqsClient.changeMessageVisibility(b -> b.queueUrl(queueUrl).receiptHandle(message.receiptHandle())
                    .visibilityTimeout(delay));
        } catch (RuntimeException e) {
            log.warn("재시도 간격 설정 실패 — 기본 가시성 타임아웃으로 재전달 messageId={}, cause={}", message.messageId(), e.toString());
        }
    }

    static int retryDelaySeconds(int receiveCount) {
        return switch (receiveCount) {
            case 0, 1 -> 60;
            case 2 -> 300;
            default -> 900;
        };
    }

    private static int receiveCount(Message message) {
        String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        try {
            return count == null ? 1 : Integer.parseInt(count);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** 원인 사슬에 DB · 네트워크 일시 장애가 있는가 */
    static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof DataAccessResourceFailureException
                    || t instanceof CannotCreateTransactionException || t instanceof SQLTransientException
                    || t instanceof SQLRecoverableException || t instanceof ConnectException) {
                return true;
            }
        }
        return false;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
