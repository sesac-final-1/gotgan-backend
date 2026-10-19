package com.sesac.gojigi.settlement.settlement.service;

import com.sesac.gojigi.settlement.common.event.SnsEventPublisher;
import com.sesac.gojigi.settlement.settlement.dto.event.SettlementPaidPayload;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 지급 트랜잭션이 커밋된 뒤에만 SettlementPaid 발행 (가이드라인 10장 1단계 — 롤백된 지급의 이벤트가 나가지 않음).
 * 커밋 직후 발행이 실패하면 명세서 메일이 빠질 수 있다 — 로그로 남기고, 판매자는 화면에서 명세서 링크를 받을 수 있다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SettlementPaidPublisher {

    private final SnsEventPublisher snsEventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPaid(SettlementPaidPayload payload) {
        try {
            snsEventPublisher.publish("SettlementPaid", String.valueOf(payload.settlementId()), payload);
        } catch (RuntimeException e) {
            log.error("SettlementPaid 발행 실패 — 명세서 메일 누락 가능 settlementId={} cause={}", payload.settlementId(), e.toString());
        }
    }
}
