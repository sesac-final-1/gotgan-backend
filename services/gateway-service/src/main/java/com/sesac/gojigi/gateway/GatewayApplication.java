package com.sesac.gojigi.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * gateway-service (:8080) — Spring Cloud Gateway · JWT 검증(Keycloak JWKS) · 사용자 헤더 주입 · CORS
 * 담당: perish95 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
