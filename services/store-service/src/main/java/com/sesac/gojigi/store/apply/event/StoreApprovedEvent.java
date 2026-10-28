package com.sesac.gojigi.store.apply.event;

import com.sesac.gojigi.store.apply.dto.event.StoreApprovedPayload;
import java.time.LocalDateTime;

/** 승인 트랜잭션 안에서 던지는 스프링 내부 이벤트 — 커밋된 뒤 StoreApprovedPublisher 가 SNS 로 보낸다 */
public record StoreApprovedEvent(Long applicationId, LocalDateTime decidedAt, StoreApprovedPayload payload) {
}
