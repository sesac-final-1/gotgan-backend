package com.sesac.gojigi.settlement.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각 — Asia/Seoul (컨벤션 3-5). 날짜 계산(지난주 · 이번 달 · 적용 시작일 검증)은 이 Clock 으로 — 테스트에서 고정 시각으로 바꿀 수 있게 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
