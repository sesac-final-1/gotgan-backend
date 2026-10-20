package com.sesac.gojigi.common.web.exception;

import org.springframework.http.HttpStatus;

/**
 * 모든 에러 코드 enum 이 구현하는 인터페이스 (컨벤션 2-5).
 * 코드 문자열은 의미형 {도메인}_{상황} 이고 enum 상수명과 같다 — 보통 getCode() 는 name() 을 반환한다.
 */
public interface ErrorCode {

    HttpStatus getStatus();

    String getCode();

    String getMessage();
}
