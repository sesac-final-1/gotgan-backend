package com.sesac.gojigi.common.web.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.common.web.logging.UserIdMdcFilter;
import com.sesac.gojigi.common.web.observation.ActuatorObservationPredicate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommonWebAutoConfigurationTest {

    private final AutoConfigurations config = AutoConfigurations.of(CommonWebAutoConfiguration.class);

    @Test
    @DisplayName("Servlet 앱에서는 공통 예외 핸들러 · userId MDC 필터가 등록된다")
    void servlet() {
        new WebApplicationContextRunner().withConfiguration(config)
                .run(context -> {
                    assertThat(context).hasSingleBean(GlobalExceptionHandler.class);
                    assertThat(context).hasSingleBean(UserIdMdcFilter.class);
                    assertThat(context).hasSingleBean(ActuatorObservationPredicate.class);
                });
    }

    @Test
    @DisplayName("app.tracing.exclude-actuator=false 면 /actuator 관측 제외를 끈다")
    void actuatorExclusion_disabledByProperty() {
        new WebApplicationContextRunner().withConfiguration(config)
                .withPropertyValues("app.tracing.exclude-actuator=false")
                .run(context -> assertThat(context).doesNotHaveBean(ActuatorObservationPredicate.class));
    }

    @Test
    @DisplayName("app.logging.user-id-mdc=false 면 userId MDC 필터를 끈다")
    void userIdMdc_disabledByProperty() {
        new WebApplicationContextRunner().withConfiguration(config)
                .withPropertyValues("app.logging.user-id-mdc=false")
                .run(context -> assertThat(context).doesNotHaveBean(UserIdMdcFilter.class));
    }

    @Test
    @DisplayName("WebFlux(gateway) · 비웹 앱에서는 켜지지 않는다")
    void notServlet() {
        new ReactiveWebApplicationContextRunner().withConfiguration(config)
                .run(context -> assertThat(context).doesNotHaveBean(CommonWebAutoConfiguration.class));
        new ApplicationContextRunner().withConfiguration(config)
                .run(context -> assertThat(context).doesNotHaveBean(CommonWebAutoConfiguration.class));
    }

    @Test
    @DisplayName("서비스가 핸들러를 직접 정의하면 그것을 쓴다")
    void customHandler() {
        new WebApplicationContextRunner().withConfiguration(config)
                .withUserConfiguration(CustomHandlerConfig.class)
                .run(context -> assertThat(context).getBean(GlobalExceptionHandler.class)
                        .isSameAs(context.getBean("customHandler")));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomHandlerConfig {
        @Bean
        GlobalExceptionHandler customHandler() {
            return new GlobalExceptionHandler();
        }
    }
}
