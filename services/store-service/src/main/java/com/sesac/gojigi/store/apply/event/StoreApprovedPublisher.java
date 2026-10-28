package com.sesac.gojigi.store.apply.event;

import com.sesac.gojigi.store.common.event.EventEnvelope;
import com.sesac.gojigi.store.common.event.SnsEventPublisher;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.json.JsonMapper;

/**
 * StoreApproved 발행 — 승인이 커밋된 뒤(AFTER_COMMIT) 요청 스레드에서 바로 SNS 로 보낸다. 아웃박스는 쓰지 않는다 (store.md 관리자 내부 API 공통).
 * eventId · occurredAt 이 신청 ID · 승인 시각으로 정해져 다시 보내도 같은 봉투 — 구독자 processed_event · admin 알림함 키가 중복을 거른다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreApprovedPublisher {

    static final String EVENT_TYPE = "StoreApproved";
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final SnsEventPublisher snsEventPublisher;
    private final JsonMapper jsonMapper;

    /** 실패해도 승인 응답에는 영향 없음 — 판매자 권한이 안 붙으므로 사람이 확인하도록 error + 스택, 같은 eventId 로 다시 보내면 된다 */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(StoreApprovedEvent event) {
        String eventId = eventId(event.applicationId());
        String storeId = String.valueOf(event.payload().storeId());
        try {
            EventEnvelope<?> envelope = new EventEnvelope<>(eventId, EVENT_TYPE,
                    event.decidedAt().atZone(KST).toOffsetDateTime(), storeId, 1, event.payload());
            snsEventPublisher.send(EVENT_TYPE, storeId, eventId, jsonMapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.error("StoreApproved 발행 실패 - applicationId={}, storeId={}, eventId={}",
                    event.applicationId(), storeId, eventId, e);
        }
    }

    /** StoreApproved:{applicationId} 의 이름 기반 UUID (v3) — 신청 하나에 항상 같은 값 */
    static String eventId(Long applicationId) {
        return UUID.nameUUIDFromBytes((EVENT_TYPE + ":" + applicationId).getBytes(StandardCharsets.UTF_8)).toString();
    }
}
