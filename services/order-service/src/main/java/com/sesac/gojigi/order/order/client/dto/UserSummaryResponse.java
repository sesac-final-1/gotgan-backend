package com.sesac.gojigi.order.order.client.dto;

/**
 * user 회원 조회 — GET /internal/users/{userId} 응답 중 주문자 스냅샷에 쓰는 값만 (모르는 필드는 무시).
 * name 은 실명(알림 메일용), nick 이 있으면 화면 표시명은 nick.
 */
public record UserSummaryResponse(Long userId, String email, String name, String nick) {
}
