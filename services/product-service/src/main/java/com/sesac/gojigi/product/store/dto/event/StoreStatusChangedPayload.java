package com.sesac.gojigi.product.store.dto.event;

/** StoreStatusChanged payload (config docs/events.md · store.md 9절) — status: ACTIVE · SUSPENDED · CLOSED */
public record StoreStatusChangedPayload(Long storeId, String status) {
}
