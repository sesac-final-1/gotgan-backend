package com.sesac.gojigi.user.common.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄러 — 이용정지 기간 만료 자동 해제(SuspensionExpiryScheduler).
 * ShedLock 잠금 저장소 = userdb.shedlock (V3). 레플리카가 여러 개여도 @Scheduled 작업은 한 파드만 (가이드라인 10장 스케줄러 함정).
 * usingDbTime: 파드마다 시계가 달라도 DB 시각 기준으로 잠금 만료를 판단.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "5m")
public class SchedulingConfig {

    @Bean
    public LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }
}
