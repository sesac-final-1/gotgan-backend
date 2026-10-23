package com.sesac.gojigi.user.user.dto.event;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * StoreApproved payload (docs/events.md) — user 는 storeId · userId 만 쓴다.
 * storeName · sellerEmail · settlementAccount 는 settlement · 알림용이라 읽지 않는다.
 *
 * @param userId 입점 신청자 회원 번호 (신청 때 X-User-Id)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StoreApprovedPayload(Long storeId, Long userId) {
}
