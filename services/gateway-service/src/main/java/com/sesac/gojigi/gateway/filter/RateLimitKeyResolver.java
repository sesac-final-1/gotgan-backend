package com.sesac.gojigi.gateway.filter;

import java.util.regex.Pattern;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * 요청 제한(RequestRateLimiter) 키 — 누구 몫으로 셀지. RedisRateLimiter 가 라우트 ID 를 붙여 라우트마다 따로 센다.
 * - 로그인: user:{userId} (토큰의 user_id)
 * - 비로그인 + Vercel 경유: ip:{X-Vercel-Forwarded-For} — Vercel 이 직접 채우는 헤더라 클라이언트가 바꿀 수 없다
 * - 비로그인 + 직접(api.gotgan.click): 공용 키 direct
 * X-Forwarded-For 는 쓰지 않는다 — Vercel 을 거쳐도 클라이언트가 보낸 값이 그대로 전달된다(11/02 캡처 확인).
 * 연결 주소도 쓰지 않는다 — NLB(TLS 종료, preserve_client_ip=false) 뒤라 NLB 내부 IP 2개뿐이다.
 * 직접 들어오는 비로그인 정상 요청은 제한을 붙인 라우트에 없어서(Keycloak · 웹훅 · 채팅 WS 는 제외, SSE 는 토큰 있음) 한 키로 묶어도 정상 사용자는 막히지 않는다.
 * 남는 구멍: 직접 호출에 X-Vercel-Forwarded-For 를 지어내 넣으면 구분할 수 없다 — 2차로 Vercel 비밀 헤더(노션 gateway-service 정리).
 */
@Component
public class RateLimitKeyResolver implements KeyResolver {

    static final String VERCEL_FORWARDED_FOR = "X-Vercel-Forwarded-For";
    static final String DIRECT = "direct";

    /** IPv4 · IPv6 문자만, 최대 45자(IPv6 최대 길이) — 지어낸 긴 값으로 Redis 키를 늘리지 못하게 */
    private static final Pattern IP = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

    @Override
    public Mono<String> resolve(ServerWebExchange exchange) {
        return AuthenticatedUser.current()
                .filter(AuthenticatedUser::linked)
                .map(user -> "user:" + user.userId())
                .switchIfEmpty(Mono.fromSupplier(() -> anonymousKey(exchange.getRequest().getHeaders())));
    }

    static String anonymousKey(HttpHeaders headers) {
        String forwarded = headers.getFirst(VERCEL_FORWARDED_FOR);
        if (forwarded == null) {
            return DIRECT;
        }
        String ip = forwarded.split(",", 2)[0].trim();   // 혹시 여러 개면 첫 번째(클라이언트)
        return IP.matcher(ip).matches() ? "ip:" + ip : DIRECT;
    }
}
