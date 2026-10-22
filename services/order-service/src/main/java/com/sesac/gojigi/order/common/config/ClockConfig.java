package com.sesac.gojigi.order.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각 — Asia/Seoul (컨벤션 3-5). 비즈니스 시각(주문번호 날짜 · 결제 시각 · 재시도 시각)은 이 Clock 으로 — 테스트에서 고정 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
