package com.sesac.gojigi.order.order.scheduler;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;

import com.sesac.gojigi.order.order.service.DeliveryTxService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AutoDeliverSchedulerTest {

    @Mock private DeliveryTxService deliveryTx;

    @Test
    @DisplayName("now - auto-deliver-after 전에 발송된 상품주문을 배송완료, 하나가 실패(판매자가 먼저 처리)해도 다음 건 계속")
    void deliverShipped() {
        // given — 12:00, 1시간 기준
        Clock clock = Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        given(deliveryTx.findShippedBefore(LocalDateTime.of(2026, 10, 26, 11, 0), 100)).willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("already delivered")).given(deliveryTx).deliverBySystem(1L);
        AutoDeliverScheduler scheduler = new AutoDeliverScheduler(deliveryTx, clock, Duration.ofHours(1), 100);

        // when
        scheduler.deliverShipped();

        // then
        verify(deliveryTx).deliverBySystem(2L);
    }
}
