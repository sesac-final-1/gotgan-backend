package com.sesac.gojigi.common.web.logging;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 요청한 사용자 ID 를 로그 MDC "userId" 로 — 예외 처리기 등 어디서 남긴 로그든 누가 요청했는지 보이게 (컨벤션 5-6 · 2-8, 10/21 이경석 요청).
 * 값은 게이트웨이가 JWT 로 넣어 준 X-User-Id. Loki JSON 은 MDC 를 접두어 없이 내보내 "userId":"7" 필드가 된다(라벨은 아님 — 카디널리티).
 * 숫자일 때만 넣는다 — 게이트웨이를 거치지 않은 요청에 이상한 값(줄바꿈 등)이 와도 로그가 오염되지 않게.
 * 헤더가 없으면(/internal/** · 공개 경로) 넣지 않는다. 요청이 끝나면 반드시 지운다(스레드 재사용 시 이전 요청 값이 남지 않게).
 */
public class UserIdMdcFilter extends OncePerRequestFilter implements Ordered {

    public static final String MDC_KEY = "userId";

    /** 추적(traceId) 필터 다음, 업무 필터보다 앞 — 이후의 모든 로그에 붙도록 */
    static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 10;

    private static final Pattern NUMERIC_ID = Pattern.compile("\\d{1,19}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String userId = request.getHeader(CurrentUserArgumentResolver.USER_ID_HEADER);
        if (userId == null || !NUMERIC_ID.matcher(userId).matches()) {
            chain.doFilter(request, response);
            return;
        }
        MDC.put(MDC_KEY, userId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
