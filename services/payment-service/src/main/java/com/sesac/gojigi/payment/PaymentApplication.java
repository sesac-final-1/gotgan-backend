package com.sesac.gojigi.payment;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * payment-service (:8086) — 결제 · 포트원 V2 검증/취소 · 웹훅
 * 담당: jongbeen97 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
public class PaymentApplication {

    public static void main(String[] args) {
        SpringApplication.run(PaymentApplication.class, args);
    }
}
