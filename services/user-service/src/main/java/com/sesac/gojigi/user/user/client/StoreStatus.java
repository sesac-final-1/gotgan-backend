package com.sesac.gojigi.user.user.client;

/**
 * 스토어 상태 — storedb store.status 와 같은 값 (ACTIVE / SUSPENDED / CLOSED).
 * TODO(store 개발 완료 시): 폐점(CLOSED)은 storedb 에 아직 없다 — store-service 가 폐점 상태(상태 추가 또는 deleted_at)를 정하면 맞춘다.
 */
public enum StoreStatus {
    ACTIVE,
    SUSPENDED,
    CLOSED;

    public boolean isClosed() {
        return this == CLOSED;
    }
}
