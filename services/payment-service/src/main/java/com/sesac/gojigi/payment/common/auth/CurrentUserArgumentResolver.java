package com.sesac.gojigi.payment.common.auth;

import com.sesac.gojigi.payment.common.exception.BusinessException;
import com.sesac.gojigi.payment.common.exception.CommonErrorCode;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** X-User-* 헤더 → AuthUser. 헤더가 없으면 UNAUTHORIZED (게이트웨이를 거치지 않은 요청). */
public class CurrentUserArgumentResolver implements HandlerMethodArgumentResolver {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLES_HEADER = "X-User-Roles";
    public static final String STORE_ID_HEADER = "X-Store-Id";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(CurrentUser.class)
                && AuthUser.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        String userId = webRequest.getHeader(USER_ID_HEADER);
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(CommonErrorCode.UNAUTHORIZED);
        }
        String rolesHeader = webRequest.getHeader(USER_ROLES_HEADER);
        List<String> roles = rolesHeader == null || rolesHeader.isBlank()
                ? List.of()
                : Arrays.stream(rolesHeader.split(",")).map(String::trim).toList();
        String storeId = webRequest.getHeader(STORE_ID_HEADER);
        Long storeIdValue = storeId == null || storeId.isBlank() ? null : Long.valueOf(storeId);
        return new AuthUser(Long.valueOf(userId), roles, storeIdValue);
    }
}
