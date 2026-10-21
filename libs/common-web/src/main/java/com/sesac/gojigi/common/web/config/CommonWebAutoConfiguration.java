package com.sesac.gojigi.common.web.config;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import com.sesac.gojigi.common.web.logging.UserIdMdcFilter;
import com.sesac.gojigi.common.web.observation.ActuatorObservationPredicate;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 의존성만 추가하면 켜지는 자동 설정 — 공통 예외 처리(GlobalExceptionHandler) · @CurrentUser 리졸버 · 로그 MDC userId 필터 · /actuator 관측 제외.
 * Servlet(Spring MVC) 앱에서만 — WebFlux 인 gateway 에서는 켜지지 않는다 (10/20 이경석).
 * CORS 는 게이트웨이가 처리하므로 여기서 열지 않는다.
 */
@AutoConfiguration
@ConditionalOnWebApplication(type = Type.SERVLET)
public class CommonWebAutoConfiguration implements WebMvcConfigurer {

    @Bean
    @ConditionalOnMissingBean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    /** 서블릿 Filter 빈은 Boot 가 자동 등록한다(순서는 UserIdMdcFilter.getOrder). 끄려면 app.logging.user-id-mdc=false */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.logging.user-id-mdc", havingValue = "true", matchIfMissing = true)
    public UserIdMdcFilter userIdMdcFilter() {
        return new UserIdMdcFilter();
    }

    /** Boot 가 ObservationPredicate 빈을 ObservationRegistry 에 자동 적용한다. 끄려면 app.tracing.exclude-actuator=false */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(name = "app.tracing.exclude-actuator", havingValue = "true", matchIfMissing = true)
    public ActuatorObservationPredicate actuatorObservationPredicate() {
        return new ActuatorObservationPredicate();
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
