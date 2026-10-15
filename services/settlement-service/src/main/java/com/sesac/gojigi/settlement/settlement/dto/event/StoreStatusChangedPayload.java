package com.sesac.gojigi.settlement.settlement.dto.event;

/** StoreStatusChanged payload (docs/events.md) — status: ACTIVE / DORMANT / SUSPENDED */
public record StoreStatusChangedPayload(Long storeId, String status) {
}
