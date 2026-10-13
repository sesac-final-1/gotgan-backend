package com.sesac.gojigi.review.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 컨트롤러 파라미터에 붙이면 게이트웨이가 넣은 X-User-* 헤더를 AuthUser 로 받는다 (컨벤션 2-4). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CurrentUser {
}
