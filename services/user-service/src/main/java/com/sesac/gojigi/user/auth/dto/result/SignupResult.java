package com.sesac.gojigi.user.auth.dto.result;

/** SignupService → AuthController. login 이 null 이면 자동 로그인 실패 (가입은 완료) */
public record SignupResult(Long userId, LoginResult login) {

    public static SignupResult loggedIn(Long userId, LoginResult login) {
        return new SignupResult(userId, login);
    }

    public static SignupResult withoutLogin(Long userId) {
        return new SignupResult(userId, null);
    }
}
