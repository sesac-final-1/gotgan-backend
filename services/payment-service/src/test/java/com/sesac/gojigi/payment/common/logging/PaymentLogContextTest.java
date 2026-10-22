package com.sesac.gojigi.payment.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class PaymentLogContextTest {

    @AfterEach
    void clear() {
        MDC.clear();
    }

    @Test
    @DisplayName("orderNo · sagaId 를 MDC 에 넣고 clear 로 둘 다 지운다")
    void putAndClear() {
        PaymentLogContext.put("20261022-000001", "6c1f0a6e-0000-0000-0000-000000000001");

        assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isEqualTo("20261022-000001");
        assertThat(MDC.get(PaymentLogContext.SAGA_ID)).isEqualTo("6c1f0a6e-0000-0000-0000-000000000001");

        PaymentLogContext.clear();
        assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isNull();
        assertThat(MDC.get(PaymentLogContext.SAGA_ID)).isNull();
    }

    @Test
    @DisplayName("값이 없거나 빈 문자열이면 넣지 않는다 (웹훅 · 상태 조회는 sagaId 없음)")
    void skipsBlank() {
        PaymentLogContext.put("20261022-000001", null);
        PaymentLogContext.put(" ", "");

        assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isEqualTo("20261022-000001");
        assertThat(MDC.get(PaymentLogContext.SAGA_ID)).isNull();
    }

    @Test
    @DisplayName("필터는 요청이 끝나면 업무 키를 지운다 — 예외로 끝나도")
    void filterClearsAfterRequest() throws Exception {
        PaymentLogContextFilter filter = new PaymentLogContextFilter();
        MockFilterChain putsKeys = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                PaymentLogContext.put("20261022-000001", "saga-1");
            }
        };
        filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), putsKeys);
        assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isNull();

        MockFilterChain throwsAfterPut = new MockFilterChain() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response) {
                PaymentLogContext.put("20261022-000002", "saga-2");
                throw new IllegalStateException("처리 중 오류");
            }
        };
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), throwsAfterPut))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(PaymentLogContext.ORDER_NO)).isNull();
        assertThat(MDC.get(PaymentLogContext.SAGA_ID)).isNull();
    }
}
