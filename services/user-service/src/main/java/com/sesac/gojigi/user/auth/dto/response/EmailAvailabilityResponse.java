package com.sesac.gojigi.user.auth.dto.response;

/** 이메일 중복 확인 결과 — available=false 면 이미 가입된 이메일 (탈퇴 회원 포함) */
public record EmailAvailabilityResponse(String email, boolean available) {
}
