package com.sesac.gojigi.store.apply.dto.event;

/**
 * StoreApproved payload (config docs/events.md · store.md 5절). 구독자 user · settlement · 알림 Lambda · admin 알림 센터가 이 이름 그대로 읽는다.
 * 필드는 추가만 — 삭제 · 이름 변경 금지.
 */
public record StoreApprovedPayload(
        Long storeId,
        Long userId,              // 신청 때의 X-User-Id — user 가 Keycloak store_id · SELLER 를 넣는 대상
        String storeName,
        String sellerEmail,       // 신청서 managerEmail — 승인 메일 수신자
        SettlementAccount settlementAccount) {

    /** settlement 가 정산 계좌 스냅샷으로 저장 */
    public record SettlementAccount(String bank, String number, String holder) {
    }
}
