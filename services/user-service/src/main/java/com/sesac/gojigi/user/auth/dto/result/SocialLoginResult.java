package com.sesac.gojigi.user.auth.dto.result;

/**
 * 소셜 로그인 콜백 결과 — 브라우저 리다이렉트로 끝나서 예외 대신 결과로 돌려준다.
 * 성공: login(Refresh 쿠키를 내리고 결과 페이지로), 실패: errorCode(결과 페이지 ?error=)
 */
public record SocialLoginResult(LoginResult login, String errorCode) {

    public static SocialLoginResult success(LoginResult login) {
        return new SocialLoginResult(login, null);
    }

    public static SocialLoginResult failure(String errorCode) {
        return new SocialLoginResult(null, errorCode);
    }

    public boolean succeeded() {
        return login != null;
    }
}
