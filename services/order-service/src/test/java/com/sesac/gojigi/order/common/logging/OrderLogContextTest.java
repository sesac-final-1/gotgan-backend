package com.sesac.gojigi.order.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 업무 키 MDC — payment(PaymentLogContext)와 같은 키 이름 */
class OrderLogContextTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("값이 있는 키만 넣고, clear 는 둘 다 지운다")
    void put_onlyPresentValues_andClear() {
        // when
        OrderLogContext.put("20261022-000123", " ");

        // then
        assertThat(MDC.get("orderNo")).isEqualTo("20261022-000123");
        assertThat(MDC.get("sagaId")).isNull();

        OrderLogContext.put(null, "saga-1");
        assertThat(MDC.get("sagaId")).isEqualTo("saga-1");
        OrderLogContext.clear();
        assertThat(MDC.get("orderNo")).isNull();
        assertThat(MDC.get("sagaId")).isNull();
    }

    @Test
    @DisplayName("필터는 요청이 예외로 끝나도 업무 키를 지운다")
    void filter_clearsEvenOnException() {
        // given
        OrderLogContextFilter filter = new OrderLogContextFilter();

        // when · then
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(),
                (request, response) -> {
                    OrderLogContext.put("20261022-000123", "saga-1");
                    throw new ServletException("boom");
                })).isInstanceOf(ServletException.class);
        assertThat(MDC.get("orderNo")).isNull();
        assertThat(MDC.get("sagaId")).isNull();
    }
}
