package com.sesac.gojigi.order.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * ShedLock 잠금 저장소 — orderdb.shedlock (V1). 레플리카가 2개여도 결제 대기 만료 · Saga 복구 스케줄러는 한 파드만 실행 (가이드라인 10장 스케줄러 함정).
 * usingDbTime: 파드마다 시계가 달라도 DB 시각 기준으로 잠금 만료를 판단.
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
