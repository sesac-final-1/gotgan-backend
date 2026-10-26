package com.sesac.gojigi.order.order.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeliveryTxServiceTest {

    @Test
    @DisplayName("mock 송장번호 — 택배사 접두 4자리 + 상품주문 ID 8자리, 모르는 택배사는 7000")
    void mockTrackingNo() {
        assertThat(DeliveryTxService.mockTrackingNo("CJ대한통운", 456L)).isEqualTo("651200000456");
        assertThat(DeliveryTxService.mockTrackingNo("우체국택배", 7L)).isEqualTo("609100000007");
        assertThat(DeliveryTxService.mockTrackingNo("동네택배", 7L)).isEqualTo("700000000007");
    }
}
