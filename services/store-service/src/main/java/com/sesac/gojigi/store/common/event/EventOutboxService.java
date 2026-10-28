package com.sesac.gojigi.store.common.event;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 이벤트 아웃박스 (order · settlement EventOutboxService 와 같은 방식) — 이벤트를 업무 트랜잭션 안에서 outbox_event 에 남기고, 커밋 후 SNS 로 보낸다.
 * ① enqueue: 업무 트랜잭션 안에서만(MANDATORY) — 업무가 롤백되면 이벤트도 남지 않는다
 * ② 커밋 직후 바로 한 번 발행(AFTER_COMMIT) — 정상일 때 지연 없음, SNS 는 1초에서 끊는다(AwsConfig)
 * ③ 실패 · 미발행은 발행기(publish-interval, ShedLock)가 다시 — SNS 가 죽어도 업무는 진행되고 복구되면 자동으로 나간다
 * 최소 1회 전달: 같은 행을 두 번 보낼 수 있으나 eventId 가 같아 소비자가 거른다. eventId · occurredAt 은 부르는 쪽이 정해 행에 남긴다.
 */
@Slf4j
@Service
public class EventOutboxService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int ENVELOPE_VERSION = 1;

    private final OutboxEventRepository outboxRepository;
    private final SnsEventPublisher snsEventPublisher;
    private final ApplicationEventPublisher eventPublisher;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final Duration retryAfter;
    private final int maxAttempts;

    /** 커밋 후 바로 보낼 행 — AFTER_COMMIT 리스너로 넘기는 신호 */
    record Enqueued(Long outboxId) {
    }

    public EventOutboxService(OutboxEventRepository outboxRepository, SnsEventPublisher snsEventPublisher,
            ApplicationEventPublisher eventPublisher, JsonMapper jsonMapper, PlatformTransactionManager transactionManager,
            Clock clock,
            @Value("${app.events.outbox.retry-after:10s}") Duration retryAfter,
            @Value("${app.events.outbox.max-attempts:10}") int maxAttempts) {
        this.outboxRepository = outboxRepository;
        this.snsEventPublisher = snsEventPublisher;
        this.eventPublisher = eventPublisher;
        this.jsonMapper = jsonMapper;
        this.tx = new TransactionTemplate(transactionManager);
        // AFTER_COMMIT 리스너 안에서도 실제로 커밋되도록 항상 새 트랜잭션 (끝난 트랜잭션에 참여하면 쓰기가 반영되지 않음)
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
        this.retryAfter = retryAfter;
        this.maxAttempts = maxAttempts;
    }

    /**
     * 업무 트랜잭션 안에서 이벤트를 남긴다. eventId 는 다시 보내도 같아야 하는 값(예: StoreApproved:{신청 ID} 이름 기반 UUID),
     * occurredAt 은 업무 시각 — 응답에 내려 준 시각과 같은 값을 넘긴다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(String eventType, String aggregateId, String eventId, LocalDateTime occurredAt, Object payload) {
        OutboxEvent saved = outboxRepository.save(OutboxEvent.of(eventId, eventType, aggregateId,
                jsonMapper.writeValueAsString(payload), occurredAt));
        eventPublisher.publishEvent(new Enqueued(saved.getId()));
    }

    /** 커밋 직후 바로 한 번 — 실패해도 업무 응답에는 영향 없음(행이 NEW 로 남아 발행기가 다시) */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommitted(Enqueued enqueued) {
        send(enqueued.outboxId());
    }

    /** 발행기 — 커밋 직후 발행이 실패했거나(SNS 장애 · 타임아웃) 그 사이 파드가 죽어 남은 행 */
    @Scheduled(fixedDelayString = "${app.events.outbox.publish-interval:30s}",
            initialDelayString = "${app.events.outbox.initial-delay:20s}")
    @SchedulerLock(name = "store-event-outbox", lockAtMostFor = "5m")
    public void publishPending() {
        LocalDateTime before = LocalDateTime.now(clock).minus(retryAfter);
        tx.execute(s -> outboxRepository.findByStatusAndOccurredAtBeforeOrderByIdAsc(OutboxEvent.Status.NEW, before,
                Limit.of(50))).forEach(row -> send(row.getId()));
    }

    /** 1건 발행 — SNS 호출은 트랜잭션 밖, 결과 기록만 짧은 트랜잭션 */
    void send(Long outboxId) {
        OutboxEvent row = tx.execute(s -> outboxRepository.findById(outboxId).orElse(null));
        if (row == null || row.isSent()) {
            return;
        }
        try {
            snsEventPublisher.send(row.getEventType(), row.getAggregateId(), row.getEventId(), envelope(row));
        } catch (RuntimeException e) {
            int attempts = record(outboxId, e.toString());
            if (attempts == maxAttempts) {
                // 사람이 봐야 하는 실패 — error + 스택트레이스는 한도에 닿은 한 번만 (컨벤션 2-8 반복 실패는 첫 회만 스택)
                log.error("이벤트 발행이 {}회 실패 — 수동 확인 필요 eventType={} aggregateId={} eventId={}",
                        attempts, row.getEventType(), row.getAggregateId(), row.getEventId(), e);
            } else if (attempts > maxAttempts) {
                log.warn("이벤트 발행 계속 실패 — 수동 확인 필요 eventType={} eventId={} attempt={} cause={}",
                        row.getEventType(), row.getEventId(), attempts, e.toString());
            } else {
                log.warn("이벤트 발행 실패 — 발행기가 다시 보냄 eventType={} aggregateId={} attempt={} cause={}",
                        row.getEventType(), row.getAggregateId(), attempts, e.toString());
            }
            return;
        }
        tx.executeWithoutResult(s -> outboxRepository.findById(outboxId)
                .ifPresent(r -> r.markSent(LocalDateTime.now(clock))));
    }

    /** 봉투 JSON (docs/events.md) — 행에 남긴 payload · eventId · occurred_at 으로 만들어 다시 보내도 같다 */
    private String envelope(OutboxEvent row) {
        JsonNode payload = jsonMapper.readTree(row.getPayload());
        return jsonMapper.writeValueAsString(new EventEnvelope<>(row.getEventId(), row.getEventType(),
                row.getOccurredAt().atZone(KST).toOffsetDateTime(), row.getAggregateId(), ENVELOPE_VERSION, payload));
    }

    private int record(Long outboxId, String error) {
        Integer attempts = tx.execute(s -> outboxRepository.findById(outboxId).map(r -> {
            r.markFailed(error);
            return r.getAttemptCount();
        }).orElse(0));
        return attempts == null ? 0 : attempts;
    }
}
