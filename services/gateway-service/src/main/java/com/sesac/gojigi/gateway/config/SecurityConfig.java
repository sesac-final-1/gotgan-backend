package com.sesac.gojigi.gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * 거친 인가(역할) 는 여기서, 세밀한 인가(소유권) 는 각 서비스에서 (가이드라인 08장).
 * 경로 규칙은 정리 문서 8-3 · 컨벤션 2-4. Keycloak 이 발급한 JWT 를 issuer-uri 의 JWKS 로 검증한다.
 * TODO 이경석: roles 클레임 → ROLE_* 권한 변환기(jwtAuthenticationConverter) 연결 — 연결 전까지 hasRole 은 항상 거부된다.
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(auth -> auth
                        .pathMatchers("/api/auth/**").permitAll()                                   // 가입 · 로그인 · refresh (user-service 가 Keycloak 대리 호출)
                        .pathMatchers(HttpMethod.POST, "/api/payments/webhook").permitAll()          // 포트원 웹훅 (서명은 payment 가 검증)
                        .pathMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .pathMatchers(HttpMethod.GET, "/api/products/**", "/api/categories/**",
                                "/api/stores/**", "/api/reviews/**", "/api/inquiries/**").permitAll()
                        .pathMatchers("/api/seller/apply/**").hasRole("BUYER")                      // 입점 신청은 구매자
                        .pathMatchers("/api/seller/**").hasRole("SELLER")
                        .pathMatchers("/api/admin/**").hasRole("ADMIN")                             // 전부 admin-service 로
                        .anyExchange().authenticated())
                .oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()))
                .build();
    }
}
