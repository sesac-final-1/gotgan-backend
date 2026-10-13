package com.sesac.gojigi.store;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * store-service (:8082) — 입점 신청 · 심사 · 서류 · 스토어 · 판매자 대시보드(조합)
 * 담당: VoC-100 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class StoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(StoreApplication.class, args);
    }
}
