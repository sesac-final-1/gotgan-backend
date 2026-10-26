package com.sesac.gojigi.admin.common.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 현재 시각 — Asia/Seoul (컨벤션 3-5). 신규 상위 카테고리 수수료의 적용 시작일(오늘)을 이 Clock 으로 — 테스트에서 고정할 수 있게 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
