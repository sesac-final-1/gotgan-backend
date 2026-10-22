package com.sesac.gojigi.payment.common.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청이 끝나면 PaymentLogContext 의 업무 키를 지운다 — 스레드 재사용 시 이전 요청의 orderNo 가 남지 않게.
 * 값은 서비스 진입점에서 넣고, 지우는 건 여기서 — 그래야 서비스가 예외를 던진 뒤 GlobalExceptionHandler 가 남기는 로그에도 orderNo 가 붙는다.
 */
@Component
public class PaymentLogContextFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        } finally {
            PaymentLogContext.clear();
        }
    }
}
