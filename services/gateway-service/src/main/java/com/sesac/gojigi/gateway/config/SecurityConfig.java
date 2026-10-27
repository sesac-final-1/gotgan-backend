package com.sesac.gojigi.gateway.config;

import com.sesac.gojigi.gateway.exception.GatewayAccessDeniedHandler;
import com.sesac.gojigi.gateway.exception.GatewayAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.header.StaticServerHttpHeadersWriter;

/**
 * 거친 인가(역할) 는 여기서, 세밀한 인가(소유권) 는 각 서비스에서 (가이드라인 08장).
 * 경로 규칙은 정리 문서 8-3 · 컨벤션 2-4. Keycloak 이 발급한 JWT 를 issuer-uri 의 JWKS 로 검증하고,
 * 토큰의 roles 클레임(realm 역할)을 ROLE_* 권한으로 바꿔 hasRole 로 판단한다.
 * 401 · 403 은 업무 서비스와 같은 봉투 { code, message, data } 로 응답한다.
 */
@Configuration
@EnableWebFluxSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    /**
     * API 응답 보안 헤더 (SYS-23, 10/27) — JSON 만 돌려주므로 아무것도 불러오거나 끼워 넣지 못하게.
     * 이미 같은 헤더가 있는 응답(Keycloak 로그인 화면 /realms/** — 자기 CSP · HSTS 를 보냄)은 덮어쓰지 않는다
     */
    static final String API_CSP = "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'";
    static final String HSTS = "max-age=31536000; includeSubDomains";

    private final GatewayAuthenticationEntryPoint authenticationEntryPoint;
    private final GatewayAccessDeniedHandler accessDeniedHandler;

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .headers(h -> h
                        .contentSecurityPolicy(csp -> csp.policyDirectives(API_CSP))
                        .hsts(ServerHttpSecurity.HeaderSpec.HstsSpec::disable)   // 기본 HSTS 는 https 요청에만 — NLB 가 TLS 를 끝내 파드엔 http 로 와서 직접 붙인다
                        .writer(StaticServerHttpHeadersWriter.builder().header("Strict-Transport-Security", HSTS).build()))
                .authorizeExchange(auth -> auth
                        .pathMatchers(HttpMethod.OPTIONS, "/api/notifications/stream").permitAll()   // 실시간 알림 CORS preflight — 판정은 application.yml globalcors
                        .pathMatchers(HttpMethod.GET, "/ws/chat").permitAll()                       // 채팅 WebSocket 핸드셰이크 — 토큰은 STOMP CONNECT 에서 chat-service 가 검증 (X-User-* 는 UserHeaderFilter 가 지움)
                        .pathMatchers("/api/auth/**").permitAll()                                   // 가입 · 로그인 · refresh (user-service 가 Keycloak 대리 호출)
                        .pathMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()          // 포트원 웹훅 (서명은 payment 가 검증)
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers("/realms/gotgan/**", "/resources/**").permitAll()                // Keycloak 로그인 · 소셜 복귀 화면 (라우트 keycloak)
                        .pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**",
                                "/api/stores/**", "/api/reviews/**", "/api/inquiries/**").permitAll()
                        .pathMatchers("/api/seller/apply/**").hasRole("BUYER")                      // 입점 신청은 구매자
                        .pathMatchers("/api/seller/**").hasRole("SELLER")
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")                             // 전부 admin-service 로
                        .pathMatchers("/api/reports/**").hasAnyRole("BUYER", "SELLER")             // 신고 접수 → admin-service (판매자 검토 요청 포함, 10/26)
                        .anyExchange().authenticated())
                .exceptionHandling(e -> e
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .oauth2ResourceServer(o -> o
                        .authenticationEntryPoint(authenticationEntryPoint)                         // 잘못된 · 만료 토큰도 같은 봉투
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())))
                .build();
    }

    /**
     * Keycloak realm 역할(roles 클레임, realm 설정의 매퍼) → ROLE_BUYER · ROLE_SELLER · ROLE_ADMIN.
     * Spring 기본은 scope 클레임만 SCOPE_* 로 읽어 hasRole 이 항상 거부된다.
     */
    static ReactiveJwtAuthenticationConverterAdapter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter roles = new JwtGrantedAuthoritiesConverter();
        roles.setAuthoritiesClaimName("roles");
        roles.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(roles);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }
}
