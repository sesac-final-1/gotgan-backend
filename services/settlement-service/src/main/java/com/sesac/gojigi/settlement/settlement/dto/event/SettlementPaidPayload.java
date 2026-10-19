package com.sesac.gojigi.settlement.settlement.dto.event;

/** SettlementPaid payload (docs/events.md) — 구독: Lambda settlement-report (명세서 CSV → S3 reports/{storeId}/{week}.csv → 메일) */
public record SettlementPaidPayload(Long settlementId, Long storeId, String week, long payoutAmt, String sellerEmail) {
}
