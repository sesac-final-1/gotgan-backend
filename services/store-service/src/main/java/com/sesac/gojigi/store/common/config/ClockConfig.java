package com.sesac.gojigi.store.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각 — Asia/Seoul (컨벤션 3-5). 비즈니스 시각(약관 동의 · 상태 변경 · 30일 재신청)은 이 Clock 으로 — 테스트에서 고정 시각으로 바꿀 수 있게 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
