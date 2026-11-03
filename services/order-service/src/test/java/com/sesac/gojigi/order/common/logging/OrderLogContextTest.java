package com.sesac.gojigi.order.common.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** 업무 키 MDC — payment(PaymentLogContext)와 같은 키 이름 */
class OrderLogContextTest {

    /** 사가를 직접 부르는 단위 테스트는 요청 필터가 없어 MDC(orderNo · sagaId)를 남긴 채 끝난다 — 같은 스레드의 앞 테스트 값에 흔들리지 않게 비우고 시작 */
    @BeforeEach
    void setUp() {
        MDC.clear();
    }

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
