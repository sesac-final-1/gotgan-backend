package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.commission.entity.CategoryCommission;
import com.sesac.gojigi.settlement.commission.exception.CommissionErrorCode;
import com.sesac.gojigi.settlement.commission.exception.CommissionException;
import com.sesac.gojigi.settlement.commission.repository.CategoryCommissionRepository;
import com.sesac.gojigi.settlement.common.event.EventEnvelope;
import com.sesac.gojigi.settlement.common.event.ProcessedEvent;
import com.sesac.gojigi.settlement.common.event.ProcessedEventRepository;
import com.sesac.gojigi.settlement.settlement.dto.event.OrderItemConfirmedPayload;
import com.sesac.gojigi.settlement.settlement.entity.SettlementItem;
import com.sesac.gojigi.settlement.settlement.repository.SettlementItemRepository;
import com.sesac.gojigi.settlement.settlement.util.FeeCalculator;
import java.time.LocalDateTime;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OrderItemConfirmed → 정산 대상 적재 (STL-02 · 04). 다른 서비스를 호출하지 않는다 — 수수료율은 자기 DB(category_commission)에서 찾는다.
 * 구매확정일(KST)에 유효한 상위 카테고리 수수료율을 스냅샷하고, 판매수수료 · 결제수수료를 원 단위 내림으로 계산해 저장.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettlementItemService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul"); // DB DATETIME 은 Asia/Seoul (컨벤션 3-5)

    public enum Result { LOADED, DUPLICATE }

    private final SettlementItemRepository settlementItemRepository;
    private final CategoryCommissionRepository categoryCommissionRepository;
    private final ProcessedEventRepository processedEventRepository;

    /**
     * 수수료율이 없으면 CommissionException — 처리 기록을 남기지 않아 재전달되고, 3회 뒤 DLQ 로 간다.
     * 잘못된 율(0% 등)로 적재하면 지급액이 틀어지므로 멈추는 쪽을 택함. 수수료율을 등록한 뒤 DLQ 메시지를 다시 보내면 적재된다.
     */
    @Transactional
    public Result load(EventEnvelope<OrderItemConfirmedPayload> event) {
        OrderItemConfirmedPayload p = event.payload();
        if (processedEventRepository.existsByEventId(event.eventId()) || settlementItemRepository.existsByOrderItemId(p.orderItemId())) {
            return Result.DUPLICATE;   // 같은 메시지 재전달 · 같은 상품주문의 다른 eventId 모두 한 번만
        }
        LocalDateTime confirmedAt = p.confirmedAt().atZoneSameInstant(KST).toLocalDateTime();
        CategoryCommission commission = categoryCommissionRepository
                .findFirstByCategoryIdAndEffectiveFromLessThanEqualOrderByEffectiveFromDesc(p.topCategoryId(), confirmedAt.toLocalDate())
                .orElseThrow(() -> new CommissionException(CommissionErrorCode.COMMISSION_NOT_FOUND,
                        "판매수수료율 없음 - categoryId=" + p.topCategoryId() + ", date=" + confirmedAt.toLocalDate()));
        settlementItemRepository.save(SettlementItem.builder()
                .orderItemId(p.orderItemId())
                .orderNo(p.orderNo())
                .storeId(p.storeId())
                .productId(p.productId())
                .productName(p.productName())
                .categoryId(p.topCategoryId())
                .itemAmt(p.itemAmt())
                .commissionRate(commission.getCommissionRate())
                .commissionAmt(FeeCalculator.commission(p.itemAmt(), commission.getCommissionRate()))
                .paymentFeeAmt(FeeCalculator.paymentFee(p.itemAmt()))
                .confirmedAt(confirmedAt)
                .build());
        processedEventRepository.save(new ProcessedEvent(event.eventId(), event.eventType()));
        log.info("정산 적재 orderItemId={} storeId={} itemAmt={} rate={} eventId={}",
                p.orderItemId(), p.storeId(), p.itemAmt(), commission.getCommissionRate(), event.eventId());
        return Result.LOADED;
    }
}
