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
 * 자동 배송완료 (DLV-04) — 택배 추적 API 대신, 발송 후 auto-deliver-after(운영 1시간 · 데모 1분)가 지난 배송중 상품주문을 배송완료로.
 * 판매자 "배송완료" 버튼과 겹치면 @Version 으로 한쪽만 반영되고, 늦은 쪽은 이미 바뀐 상태라 건너뛴다.
 */
@Slf4j
@Component
public class AutoDeliverScheduler {

    private final DeliveryTxService deliveryTx;
    private final Clock clock;
    private final Duration autoDeliverAfter;
    private final int batchSize;

    public AutoDeliverScheduler(DeliveryTxService deliveryTx, Clock clock,
                                @Value("${app.order.auto-deliver-after}") Duration autoDeliverAfter,
                                @Value("${app.scheduler.batch-size:100}") int batchSize) {
        this.deliveryTx = deliveryTx;
        this.clock = clock;
        this.autoDeliverAfter = autoDeliverAfter;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.auto-deliver.interval:60s}",
            initialDelayString = "${app.scheduler.auto-deliver.initial-delay:30s}")
    @SchedulerLock(name = "auto-deliver", lockAtMostFor = "5m")   // 레플리카가 2개여도 한 파드만
    public void deliverShipped() {
        LocalDateTime before = LocalDateTime.now(clock).minus(autoDeliverAfter);
        for (Long orderItemId : deliveryTx.findShippedBefore(before, batchSize)) {
            try {
                deliveryTx.deliverBySystem(orderItemId);
            } catch (RuntimeException e) {
                // 그 사이 판매자가 먼저 처리(상태 · @Version 충돌) — 다음 주기에는 대상이 아니다
                log.warn("자동 배송완료 건너뜀 orderItemId={} cause={}", orderItemId, e.toString());
            }
        }
    }
}
