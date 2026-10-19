package com.sesac.gojigi.user;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * user-service (:8081) — 회원 · 가입/로그인(Keycloak 대리 호출) · 배송지 · 포인트 · 등급
 * 담당: perish95 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@ConfigurationPropertiesScan   // app.* 설정 record (@ConfigurationProperties) 등록
public class UserApplication {

    public static void main(String[] args) {
        SpringApplication.run(UserApplication.class, args);
    }
}
