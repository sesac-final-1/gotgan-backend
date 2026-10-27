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
class AutoConfirmSchedulerTest {

    @Mock private DeliveryTxService deliveryTx;

    @Test
    @DisplayName("now - auto-confirm-after 전에 배송완료된 상품주문을 구매확정, 하나가 실패(구매자가 먼저 확정 · 반품 신청)해도 다음 건 계속")
    void confirmDelivered() {
        // given — 2026-10-26 12:00, 7일 기준
        Clock clock = Clock.fixed(Instant.parse("2026-10-26T03:00:00Z"), ZoneId.of("Asia/Seoul"));
        given(deliveryTx.findDeliveredBefore(LocalDateTime.of(2026, 10, 19, 12, 0), 100)).willReturn(List.of(1L, 2L));
        willThrow(new IllegalStateException("already confirmed")).given(deliveryTx).confirmBySystem(1L);
        AutoConfirmScheduler scheduler = new AutoConfirmScheduler(deliveryTx, clock, Duration.ofDays(7), 100);

        // when
        scheduler.confirmDelivered();

        // then
        verify(deliveryTx).confirmBySystem(2L);
    }
}
