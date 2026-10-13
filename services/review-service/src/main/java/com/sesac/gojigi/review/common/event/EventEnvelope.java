package com.sesac.gojigi.review.common.event;

import java.time.OffsetDateTime;

/**
 * 이벤트 봉투 — 전 팀 같은 형식 (컨벤션 2-7 · docs/events.md).
 * occurredAt 은 오프셋 포함 ISO-8601 (2026-10-21T09:12:33+09:00). 필드 추가는 자유, 삭제 · 이름 변경 · 의미 변경 금지.
 */
public record EventEnvelope<T>(String eventId, String eventType, OffsetDateTime occurredAt,
                               String aggregateId, int version, T payload) {
}
