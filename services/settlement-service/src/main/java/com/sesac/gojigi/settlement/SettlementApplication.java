package com.sesac.gojigi.settlement;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * settlement-service (:8085) — 정산 적재 · 주간 집계 · 지급 · 보류 · 카테고리별 수수료
 * 담당: jihoon-devstu · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class SettlementApplication {

    public static void main(String[] args) {
        SpringApplication.run(SettlementApplication.class, args);
    }
}
