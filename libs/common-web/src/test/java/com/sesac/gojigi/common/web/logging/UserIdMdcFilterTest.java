package com.sesac.gojigi.common.web.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class UserIdMdcFilterTest {

    private final UserIdMdcFilter filter = new UserIdMdcFilter();
    private final AtomicReference<String> seen = new AtomicReference<>();

    /** 요청을 처리하는 동안(체인 안) MDC 에 무엇이 있었는지 기록 */
    private final MockFilterChain chain = new MockFilterChain() {
        @Override
        public void doFilter(jakarta.servlet.ServletRequest request, jakarta.servlet.ServletResponse response) {
            seen.set(MDC.get(UserIdMdcFilter.MDC_KEY));
        }
    };

    @AfterEach
    void clear() {
        MDC.clear();
    }

    private static MockHttpServletRequest request(String userId) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/seller/settlements");
        if (userId != null) {
            request.addHeader("X-User-Id", userId);
        }
        return request;
    }

    @Test
    @DisplayName("X-User-Id 가 숫자면 요청 처리 중에는 MDC userId 가 있고, 끝나면 지워진다")
    void numericHeader_putDuringRequest_removedAfter() throws Exception {
        filter.doFilter(request("7"), new MockHttpServletResponse(), chain);

        assertThat(seen.get()).isEqualTo("7");
        assertThat(MDC.get(UserIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("헤더가 없거나(/internal · 공개 경로) 숫자가 아니면(줄바꿈 등) 넣지 않는다 — 로그 오염 방지")
    void missingOrNonNumericHeader_notPut() throws Exception {
        filter.doFilter(request(null), new MockHttpServletResponse(), chain);
        assertThat(seen.get()).isNull();

        filter.doFilter(request("7\nFAKE LOG LINE"), new MockHttpServletResponse(), new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r) {
                seen.set(MDC.get(UserIdMdcFilter.MDC_KEY));
            }
        });
        assertThat(seen.get()).isNull();
    }

    @Test
    @DisplayName("처리 중 예외가 나도 MDC 는 지워진다 — 스레드를 다시 쓸 때 이전 요청 값이 남지 않게")
    void exceptionInChain_stillRemoved() {
        MockFilterChain failing = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest q, jakarta.servlet.ServletResponse r) throws ServletException {
                throw new ServletException("boom");
            }
        };

        assertThatThrownBy(() -> filter.doFilter(request("7"), new MockHttpServletResponse(), failing))
                .isInstanceOf(ServletException.class);
        assertThat(MDC.get(UserIdMdcFilter.MDC_KEY)).isNull();
    }

    @Test
    @DisplayName("추적 필터 다음 · 업무 필터 앞 순서")
    void order() {
        assertThat(filter.getOrder()).isEqualTo(UserIdMdcFilter.ORDER);
    }
}
