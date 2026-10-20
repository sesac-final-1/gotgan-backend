package com.sesac.gojigi.common.web.config;

import com.sesac.gojigi.common.web.auth.CurrentUserArgumentResolver;
import com.sesac.gojigi.common.web.exception.GlobalExceptionHandler;
import java.util.List;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 의존성만 추가하면 켜지는 자동 설정 — 공통 예외 처리(GlobalExceptionHandler) · @CurrentUser 리졸버.
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

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new CurrentUserArgumentResolver());
    }
}
