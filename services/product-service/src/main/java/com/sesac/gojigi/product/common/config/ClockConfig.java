package com.sesac.gojigi.product.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 비즈니스 시각(소프트 삭제 등)은 이 Clock 으로 — 테스트에서 고정 시계로 바꿀 수 있게 (다른 서비스 ClockConfig 와 같음) */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
