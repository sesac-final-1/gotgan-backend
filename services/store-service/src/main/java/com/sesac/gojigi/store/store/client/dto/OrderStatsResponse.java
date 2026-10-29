package com.sesac.gojigi.store.store.client.dto;

/** order GET /internal/stats/orders?storeId= 중 폐점 판단에 쓰는 값만 — 나머지 필드(toConfirmCount 등)는 무시 */
public record OrderStatsResponse(Long storeId, long inProgressCount) {
}
