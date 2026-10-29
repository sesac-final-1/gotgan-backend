package com.sesac.gojigi.user.user.client;

/**
 * 스토어 상태 — store-service store.status 와 같은 값 (ACTIVE / SUSPENDED / CLOSED, store.md 11절).
 */
public enum StoreStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean isClosed() {
        return this == CLOSED;
    }
}
