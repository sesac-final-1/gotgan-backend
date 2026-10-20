package com.sesac.gojigi.settlement.settlement.dto.response;

import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 정산서 1건 요약 — 관리자 목록 · 판매자 목록 · 상세 머리. week = ISO 주차(2026-W43).
 * 지급액 = 매출 − 판매수수료 − 결제수수료 (원 단위 내림 합계). bank 는 지급 계좌(스토어 스냅샷)
 * carriedOverCount = itemCount 중 지난 주차에서 이월된 건수 (화면에 "이월 n건")
 */
public record SettlementSummaryResponse(
        Long id, Long storeId, String storeName, String week, LocalDate weekStart, LocalDate weekEnd,
        int itemCount, int carriedOverCount, long salesAmt, long commissionAmt, long paymentFeeAmt, long payoutAmt,
        SettlementStatus status, String holdReason, LocalDate scheduledPayDate, OffsetDateTime paidAt, Bank bank) {

    public record Bank(String bankName, String accountNumber, String accountHolder) {
    }

    public static SettlementSummaryResponse of(Settlement s, SettlementStore store) {
        return new SettlementSummaryResponse(s.getId(), s.getStoreId(), store == null ? null : store.getStoreName(),
                new SettlementWeek(s.getWeekStart()).isoWeek(), s.getWeekStart(), s.getWeekEnd(),
                s.getItemCount(), s.getCarriedOverCount(), s.getSalesAmt(), s.getCommissionAmt(), s.getPaymentFeeAmt(), s.getPayoutAmt(),
                s.getStatus(), s.getHoldReason(), s.getScheduledPayDate(),
                s.getPaidAt() == null ? null : s.getPaidAt().atZone(SettlementWeek.KST).toOffsetDateTime(),
                store == null ? null : new Bank(store.getBankName(), store.getAccountNumber(), store.getAccountHolder()));
    }
}
