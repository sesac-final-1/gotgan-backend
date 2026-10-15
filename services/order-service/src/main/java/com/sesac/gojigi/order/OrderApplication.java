package com.sesac.gojigi.order;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * order-service (:8084) — 장바구니 · 주문 · 배송(status) · 취소/반품/교환 · Saga 오케스트레이터
 * 담당: perish95 · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class OrderApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderApplication.class, args);
    }
}
