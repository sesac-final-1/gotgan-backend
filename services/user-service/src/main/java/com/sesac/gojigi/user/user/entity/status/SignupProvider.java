package com.sesac.gojigi.user.user.entity.status;

/** 가입 경로 — 표시 · 통계용 (인증 판단에는 쓰지 않음). 소셜 계정 연결은 Keycloak 이 관리 */
public enum SignupProvider {
    LOCAL,
    KAKAO,
    GOOGLE,
    NAVER
}
