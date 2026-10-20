package com.sesac.gojigi.settlement.settlement.dto.response;

import com.sesac.gojigi.settlement.settlement.entity.SettlementHistory;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStatus;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** 정산서 상세 — 건별 상품주문 · 수수료(율은 여기에만) + 상태 이력. 관리자(STL-05) · 판매자(STL-07) 공용 */
public record SettlementDetailResponse(SettlementSummaryResponse settlement, List<Item> items, List<History> history) {

    public record Item(Long orderItemId, String orderNo, String productName, OffsetDateTime confirmedAt, long itemAmt,
            Long categoryId, BigDecimal rate, long commission, long paymentFee, long payout, boolean carriedOver) {

        /** carriedOver — 구매확정일이 정산서 주차 밖 = 지난 주차에서 이월된 항목 */
        public static Item of(SettlementItem i, SettlementWeek week) {
            return new Item(i.getOrderItemId(), i.getOrderNo(), i.getProductName(),
                    i.getConfirmedAt().atZone(SettlementWeek.KST).toOffsetDateTime(), i.getItemAmt(), i.getCategoryId(),
                    i.getCommissionRate(), i.getCommissionAmt(), i.getPaymentFeeAmt(), i.payoutAmt(),
                    !week.contains(i.getConfirmedAt()));
        }
    }

    public record History(OffsetDateTime at, SettlementHistory.Actor actor, SettlementStatus from, SettlementStatus to,
            String reason) {

        public static History of(SettlementHistory h) {
            return new History(h.getCreatedAt() == null ? null : h.getCreatedAt().atZone(SettlementWeek.KST).toOffsetDateTime(),
                    h.getActorType(), h.getFromStatus(), h.getToStatus(), h.getReason());
        }
    }
}
