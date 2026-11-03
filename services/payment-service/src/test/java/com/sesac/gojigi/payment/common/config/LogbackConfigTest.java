package com.sesac.gojigi.payment.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.github.loki4j.logback.Loki4jAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LoggingInitializationContext;
import org.springframework.boot.logging.logback.LogbackLoggingSystem;
import org.springframework.mock.env.MockEnvironment;

/**
 * 이 서비스의 logback-spring.xml 이 공통 설정(libs/common-logging logback-base.xml)을 제대로 include 하는지만 본다 (관측성 담당 박지연).
 * 라벨 · traceId · 스택 트레이스 · 콘솔 형식 같은 상세 검증은 모듈의 LogbackBaseTest 한 벌. 여기서는 로그를 남기지 않아 Loki 로 보내지 않는다.
 */
class LogbackConfigTest {

    private static final String APP = "payment-service";

    private final LogbackLoggingSystem loggingSystem = new LogbackLoggingSystem(getClass().getClassLoader());

    @AfterEach
    void restoreLogging() {
        initLogging();                  // 같은 JVM 의 다른 테스트를 위해 기본 프로필(콘솔만)로 되돌린다
        System.clearProperty("LOKI_URL");
    }

    @Test
    @DisplayName("k8s 프로필: 공통 설정의 LOKI appender 가 붙고, app 라벨 값(APP)은 이 서비스 이름")
    void k8sProfile_includesCommonLoki_withServiceAppName() {
        // given — 쓰지 않는 주소. 로그를 남기지 않아 전송도 없다
        System.setProperty("LOKI_URL", "http://127.0.0.1:9/loki/api/v1/push");

        // when
        initLogging("k8s");

        // then
        assertThat(rootLogger().getAppender("LOKI")).isInstanceOf(Loki4jAppender.class);
        assertThat(rootLogger().getAppender("CONSOLE")).isNotNull();
        assertThat(context().getProperty("APP")).isEqualTo(APP);
    }

    @Test
    @DisplayName("기본 프로필(로컬): 콘솔만 — LOKI appender 없음")
    void defaultProfile_anyLog_consoleOnly() {
        // when
        initLogging();

        // then
        assertThat(rootLogger().getAppender("CONSOLE")).isNotNull();
        assertThat(rootLogger().getAppender("LOKI")).isNull();
        assertThat(context().getProperty("APP")).isEqualTo(APP);
    }

    /** 앱이 뜰 때처럼 logback-spring.xml 을 올린다. 이미 올라가 있으면 Boot 가 건너뛰므로 먼저 내린다 */
    private void initLogging(String... profiles) {
        MockEnvironment environment = new MockEnvironment().withProperty("spring.application.name", APP);
        environment.setActiveProfiles(profiles);
        loggingSystem.cleanUp();
        loggingSystem.beforeInitialize();
        loggingSystem.initialize(new LoggingInitializationContext(environment), "classpath:logback-spring.xml", null);
    }

    private static LoggerContext context() {
        return (LoggerContext) LoggerFactory.getILoggerFactory();
    }

    private static Logger rootLogger() {
        return context().getLogger(Logger.ROOT_LOGGER_NAME);
    }
}
