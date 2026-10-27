package com.sesac.gojigi.chat.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각 — Asia/Seoul (컨벤션 3-5). 메시지 시각 · 90일 보관 기준을 이 Clock 으로 — 테스트에서 고정할 수 있게 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
