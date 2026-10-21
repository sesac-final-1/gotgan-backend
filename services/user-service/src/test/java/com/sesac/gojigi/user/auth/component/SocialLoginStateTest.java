package com.sesac.gojigi.user.auth.component;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.user.auth.exception.AuthErrorCode;
import com.sesac.gojigi.user.auth.exception.AuthException;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SocialLoginStateTest {

    @Test
    @DisplayName("쿠키 값으로 저장했다가 그대로 되돌린다 — 매번 다른 state · verifier")
    void cookieValue_roundTrip() {
        SocialLoginState state = SocialLoginState.start(SocialProvider.NAVER);

        assertThat(SocialLoginState.fromCookieValue(state.cookieValue())).isEqualTo(state);
        assertThat(SocialLoginState.start(SocialProvider.NAVER).state()).isNotEqualTo(state.state());
        assertThat(state.toString()).doesNotContain(state.state()).doesNotContain(state.codeVerifier());
    }

    @Test
    @DisplayName("형식이 틀리거나 모르는 제공자 쿠키는 null")
    void fromCookieValue_invalid_returnsNull() {
        assertThat(SocialLoginState.fromCookieValue(null)).isNull();
        assertThat(SocialLoginState.fromCookieValue("kakao.only-two")).isNull();
        assertThat(SocialLoginState.fromCookieValue("facebook.s.v")).isNull();
    }

    @Test
    @DisplayName("PKCE S256 — RFC 7636 부록 B 예시 값")
    void codeChallenge_rfc7636Example() {
        SocialLoginState state = new SocialLoginState(SocialProvider.GOOGLE, "s", "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk");

        assertThat(state.codeChallenge()).isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }

    @Test
    @DisplayName("state 비교 — 같을 때만 true, null 은 false")
    void matches_comparesState() {
        SocialLoginState state = new SocialLoginState(SocialProvider.KAKAO, "abc", "v");

        assertThat(state.matches("abc")).isTrue();
        assertThat(state.matches("abd")).isFalse();
        assertThat(state.matches(null)).isFalse();
    }

    @Test
    @DisplayName("제공자 — alias 로 찾고 가입 경로로 바꾼다, 모르는 alias 는 AUTH_SOCIAL_PROVIDER_UNSUPPORTED")
    void socialProvider_fromAlias() {
        assertThat(SocialProvider.fromAlias("kakao").signupProvider()).isEqualTo(SignupProvider.KAKAO);
        assertThat(SocialProvider.fromAlias("naver").alias()).isEqualTo("naver");
        assertThatThrownBy(() -> SocialProvider.fromAlias("KAKAO"))
                .isInstanceOf(AuthException.class)
                .extracting("errorCode").isEqualTo(AuthErrorCode.AUTH_SOCIAL_PROVIDER_UNSUPPORTED);
    }

    @Test
    @DisplayName("결과 페이지 주소 — 실패면 ?error=코드")
    void socialLoginUrls_resultPages() {
        SocialLoginUrls urls = new SocialLoginUrls("https://api.example", "gotgan", "gotgan-backend",
                "https://front.example/api/auth/social/callback", "https://front.example/auth/callback");

        assertThat(urls.successUrl()).isEqualTo("https://front.example/auth/callback");
        assertThat(urls.failureUrl("AUTH_EMAIL_DUPLICATE")).isEqualTo("https://front.example/auth/callback?error=AUTH_EMAIL_DUPLICATE");
        assertThat(urls.callbackUrl()).isEqualTo("https://front.example/api/auth/social/callback");
    }
}
