package com.sesac.gojigi.settlement.settlement.dto.event;

import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * SettlementPaid payload (docs/events.md) — 구독: Lambda settlement-report (명세서 CSV → S3 reports/{storeId}/{week}.csv → 메일).
 * 명세서에 필요한 값을 이벤트에 모두 담는다(10/21 방식 B) — Lambda 는 클러스터 내부 API 를 부를 수 없고, settlement 는 S3 에 쓰지 않는다.
 * 앞의 5개 필드는 처음 계약 그대로, 뒤는 덧붙인 필드(이벤트 규칙: 추가만 허용). 항목 1건 약 250바이트 — SNS 한도 256KB 안에서 주 수백 건까지.
 */
public record SettlementPaidPayload(Long settlementId, Long storeId, String week, long payoutAmt, String sellerEmail,
        String storeName, LocalDate weekStart, LocalDate weekEnd, OffsetDateTime paidAt,
        long salesAmt, long commissionAmt, long paymentFeeAmt, List<Item> items) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 명세서 한 줄 = 상품주문 1건 (판매자 정산 상세와 같은 값) */
    public record Item(String orderNo, String productName, OffsetDateTime confirmedAt, long itemAmt,
            BigDecimal commissionRate, long commissionAmt, long paymentFeeAmt, long payoutAmt) {

        static Item of(SettlementItem i) {
            return new Item(i.getOrderNo(), i.getProductName(), i.getConfirmedAt().atZone(KST).toOffsetDateTime(),
                    i.getItemAmt(), i.getCommissionRate(), i.getCommissionAmt(), i.getPaymentFeeAmt(), i.payoutAmt());
        }
    }

    public static SettlementPaidPayload of(Settlement s, SettlementStore store, List<SettlementItem> items) {
        return new SettlementPaidPayload(s.getId(), s.getStoreId(), new SettlementWeek(s.getWeekStart()).isoWeek(),
                s.getPayoutAmt(), store.getSellerEmail(), store.getStoreName(), s.getWeekStart(), s.getWeekEnd(),
                s.getPaidAt().atZone(KST).toOffsetDateTime(), s.getSalesAmt(), s.getCommissionAmt(), s.getPaymentFeeAmt(),
                items.stream().map(Item::of).toList());
    }
}
