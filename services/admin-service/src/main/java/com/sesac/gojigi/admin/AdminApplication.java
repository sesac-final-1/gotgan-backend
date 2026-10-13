package com.sesac.gojigi.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * admin-service (:8088) — 관리자 API 진입점 · /internal/** 조합 · 관리자 대시보드 (DB 없음)
 * 담당: jihoon-devstu · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
public class AdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminApplication.class, args);
    }
}
