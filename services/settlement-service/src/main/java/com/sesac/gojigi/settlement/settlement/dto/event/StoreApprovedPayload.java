package com.sesac.gojigi.settlement.settlement.dto.event;

/** StoreApproved payload (docs/events.md) — settlement 는 스토어명 · 판매자 이메일 · 정산 계좌만 쓴다 */
public record StoreApprovedPayload(Long storeId, Long userId, String storeName, String sellerEmail,
        SettlementAccount settlementAccount) {

    public record SettlementAccount(String bank, String number, String holder) {
    }
}
