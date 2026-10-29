package com.sesac.gojigi.user.user.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.sesac.gojigi.user.user.client.StoreStatus;

/**
 * store 스토어 상세 (내부) — GET /internal/stores/{storeId} (docs/api/store.md 1절). user 는 폐점 확인에 status 만 쓴다.
 * storeName · 배송 정책 등 나머지 필드는 읽지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreResponse(Long storeId, StoreStatus status) {
}
