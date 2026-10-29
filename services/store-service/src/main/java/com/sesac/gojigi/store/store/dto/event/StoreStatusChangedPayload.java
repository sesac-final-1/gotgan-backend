package com.sesac.gojigi.store.store.dto.event;

import com.sesac.gojigi.store.store.entity.StoreStatus;

/**
 * StoreStatusChanged payload (config docs/events.md · store.md 9절). product(상품 노출) · settlement(지급 보류)가 이 이름 그대로 읽고,
 * 봉투 occurredAt 으로 옛 이벤트를 거른다(events.md 순서 규칙 A안). 필드는 추가만 — 삭제 · 이름 변경 금지.
 */
public record StoreStatusChangedPayload(
        Long storeId,
        StoreStatus status) {   // ACTIVE · SUSPENDED · CLOSED(폐점 API)
}
