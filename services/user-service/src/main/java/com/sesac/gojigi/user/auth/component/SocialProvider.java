package com.sesac.gojigi.user.auth.component;

import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import java.util.Arrays;

/**
 * 소셜 로그인 제공자 — alias 는 Keycloak realm 의 Identity Provider alias 와 같아야 한다 (kc_idp_hint).
 * 구글은 Keycloak 기본 제공자, 카카오는 OIDC, 네이버는 범용 OAuth 2.0 (keycloak README).
 */
public enum SocialProvider {
    GOOGLE("google", SignupProvider.GOOGLE),
    KAKAO("kakao", SignupProvider.KAKAO),
    NAVER("naver", SignupProvider.NAVER);

    private final String alias;
    private final SignupProvider signupProvider;

    SocialProvider(String alias, SignupProvider signupProvider) {
        this.alias = alias;
        this.signupProvider = signupProvider;
    }

    public String alias() {
        return alias;
    }

    public SignupProvider signupProvider() {
        return signupProvider;
    }

    public static SocialProvider fromAlias(String alias) {
        return Arrays.stream(values())
                .filter(provider -> provider.alias.equals(alias))
                .findFirst()
                .orElseThrow(() -> new AuthException(AuthErrorCode.AUTH_SOCIAL_PROVIDER_UNSUPPORTED));
    }
}
