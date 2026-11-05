package com.sesac.gojigi.settlement.settlement.dto.event;

import com.sesac.gojigi.settlement.settlement.entity.Settlement;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.entity.SettlementStore;
import com.sesac.gojigi.settlement.settlement.util.SettlementWeek;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.json.JsonMapper;

/**
 * SettlementPaid payload (docs/events.md) — 구독: Lambda settlement-report (명세서 CSV → S3 reports/{storeId}/{week}.csv → 메일).
 * 명세서에 필요한 값을 이벤트에 모두 담는다(10/21 방식 B) — Lambda 는 클러스터 내부 API 를 부를 수 없고, settlement 는 S3 에 쓰지 않는다.
 * 앞의 5개 필드는 처음 계약 그대로, 뒤는 덧붙인 필드(이벤트 규칙: 추가만 허용). 항목 1건 약 250바이트 — SNS 한도 256KB 안에서 주 수백 건까지.
 */
public record SettlementPaidPayload(Long settlementId, Long storeId, String week, long payoutAmt, String sellerEmail,
        String storeName, LocalDate weekStart, LocalDate weekEnd, OffsetDateTime paidAt,
        long salesAmt, long commissionAmt, long paymentFeeAmt,
        @JsonInclude(JsonInclude.Include.NON_NULL) List<Item> items,
        @JsonInclude(JsonInclude.Include.NON_NULL) String itemsGzip) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** SNS · SQS 한도 256KB — 나머지 필드 · 봉투 몫을 남긴다. 넘으면 항목을 gzip + Base64 로 (11/5, 평문 약 900건) */
    static final int INLINE_ITEMS_LIMIT = 200 * 1024;

    /** 명세서 한 줄 = 상품주문 1건 (판매자 정산 상세와 같은 값) */
    public record Item(String orderNo, String productName, OffsetDateTime confirmedAt, long itemAmt,
            BigDecimal commissionRate, long commissionAmt, long paymentFeeAmt, long payoutAmt) {

        static Item of(SettlementItem i) {
            return new Item(i.getOrderNo(), i.getProductName(), i.getConfirmedAt().atZone(KST).toOffsetDateTime(),
                    i.getItemAmt(), i.getCommissionRate(), i.getCommissionAmt(), i.getPaymentFeeAmt(), i.payoutAmt());
        }
    }

    public static SettlementPaidPayload of(Settlement s, SettlementStore store, List<SettlementItem> items, JsonMapper jsonMapper) {
        List<Item> lines = items.stream().map(Item::of).toList();
        byte[] json = jsonMapper.writeValueAsBytes(lines);
        boolean inline = json.length <= INLINE_ITEMS_LIMIT;
        return new SettlementPaidPayload(s.getId(), s.getStoreId(), new SettlementWeek(s.getWeekStart()).isoWeek(),
                s.getPayoutAmt(), store.getSellerEmail(), store.getStoreName(), s.getWeekStart(), s.getWeekEnd(),
                s.getPaidAt().atZone(KST).toOffsetDateTime(), s.getSalesAmt(), s.getCommissionAmt(), s.getPaymentFeeAmt(),
                inline ? lines : null, inline ? null : gzipBase64(json));
    }

    private static String gzipBase64(byte[] json) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(json.length / 8);
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return Base64.getEncoder().encodeToString(out.toByteArray());
    }
}
