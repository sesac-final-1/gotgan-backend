package com.sesac.gojigi.chat;

import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * chat-service (:8089) — 1:1 채팅: 스토어 톡 · 판매자 운영 상담 · 고객센터 1:1 상담 (CHT-01~05, 10/27). 계약 docs/api/chat.md
 * 담당: jihoon-devstu · 구조: 가이드라인 15장 · 컨벤션 2-1
 */
@SpringBootApplication
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
public class ChatApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatApplication.class, args);
    }
}
