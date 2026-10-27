package com.sesac.gojigi.order.order.scheduler;

import com.sesac.gojigi.order.order.service.DeliveryTxService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 자동 구매확정 (DLV-06) — 배송완료 후 auto-confirm-after(운영 7일 · 데모 2분)가 지난 배송완료 상품주문을 구매확정 + OrderItemConfirmed.
 * 진행 중 반품 · 교환은 상품주문이 반품요청 · 교환요청이라 대상이 아니다(보류). 스펙은 매일 00:10 이지만 클러스터가 밤에 내려가 있어
 * 1분 주기로 돈다 — 대상 조회는 (status, delivered_at) 범위라 가볍다. 구매자 버튼과 겹치면 @Version 으로 한쪽만, 늦은 쪽은 건너뛴다.
 */
@Slf4j
@Component
public class AutoConfirmScheduler {

    private final DeliveryTxService deliveryTx;
    private final Clock clock;
    private final Duration autoConfirmAfter;
    private final int batchSize;

    public AutoConfirmScheduler(DeliveryTxService deliveryTx, Clock clock,
                                @Value("${app.order.auto-confirm-after}") Duration autoConfirmAfter,
                                @Value("${app.scheduler.batch-size:100}") int batchSize) {
        this.deliveryTx = deliveryTx;
        this.clock = clock;
        this.autoConfirmAfter = autoConfirmAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.auto-confirm.interval:60s}",
            initialDelayString = "${app.scheduler.auto-confirm.initial-delay:30s}")
    @SchedulerLock(name = "auto-confirm", lockAtMostFor = "5m")   // 레플리카가 2개여도 한 파드만
    public void confirmDelivered() {
        LocalDateTime before = LocalDateTime.now(clock).minus(autoConfirmAfter);
        for (Long orderItemId : deliveryTx.findDeliveredBefore(before, batchSize)) {
            try {
                deliveryTx.confirmBySystem(orderItemId);
            } catch (RuntimeException e) {
                // 그 사이 구매자가 확정했거나 반품 · 교환을 신청(상태 · @Version 충돌) — 다음 주기에는 대상이 아니다
                log.warn("자동 구매확정 건너뜀 orderItemId={} cause={}", orderItemId, e.toString());
            }
        }
    }
}
