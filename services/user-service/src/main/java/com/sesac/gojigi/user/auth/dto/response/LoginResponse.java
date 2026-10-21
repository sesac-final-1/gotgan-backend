package com.sesac.gojigi.user.auth.dto.response;

import com.sesac.gojigi.user.auth.component.AccessTokenClaims;
import com.sesac.gojigi.user.user.entity.User;
import java.util.List;

/**
 * 로그인 응답 body — Access Token 은 body, Refresh Token 은 HttpOnly 쿠키 (컨벤션 4-4 · 가이드라인 08장).
 * 프론트는 accessToken 을 메모리(authStore)에만 두고, user 로 화면(판매자센터 · 관리자 메뉴)을 결정한다.
 */
public record LoginResponse(String accessToken, long expiresIn, LoginUser user) {

    public record LoginUser(Long userId, String name, List<String> roles, Long storeId) {
    }

    public static LoginResponse of(String accessToken, long expiresIn, User user, AccessTokenClaims claims) {
        return new LoginResponse(accessToken, expiresIn,
                new LoginUser(user.getId(), user.getName(), claims.roles(), claims.storeId()));
    }
}
