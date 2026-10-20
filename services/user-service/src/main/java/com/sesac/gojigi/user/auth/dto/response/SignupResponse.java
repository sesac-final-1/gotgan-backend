package com.sesac.gojigi.user.auth.dto.response;

/**
 * 회원가입 응답 — 가입 후 자동 로그인 결과를 함께 준다.
 * login 이 null 이면 가입은 됐지만 자동 로그인에 실패한 것 → 프론트는 로그인 화면으로 보낸다.
 */
public record SignupResponse(Long userId, LoginResponse login) {
}
