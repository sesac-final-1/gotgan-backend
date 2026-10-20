package com.sesac.gojigi.payment;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * payment-service (:8086) — 결제 · 포트원 V2 검증/취소 · 웹훅
 * 담당: jihoon-devstu · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
