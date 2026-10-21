package com.sesac.gojigi.user.auth.dto.result;

import com.sesac.gojigi.user.auth.dto.response.LoginResponse;

/**
 * 서비스 → 컨트롤러 — body 로 나갈 응답과 쿠키로 나갈 Refresh Token 을 나눠 전달 (Refresh Token 은 body 에 넣지 않는다)
 * 계층 간 전달용 record(서비스 내부 결과 확인용)
 * */
public record LoginResult(LoginResponse response, String refreshToken, long refreshExpiresIn) {
}
