package com.sesac.gojigi.review.inquiry.client.dto;

/** user 회원 조회 — GET /internal/users/{userId} 응답 중 표시 이름에 쓰는 값만 (모르는 필드는 무시) */
public record UserSummaryResponse(Long userId, String name, String nick) {

    /** 공개 표시 이름 — 닉네임이 있으면 닉네임, 없으면 실명 가운데를 가린다 (홍길동 → 홍*동, 홍길 → 홍*). order 주문자 스냅샷과 같은 규칙 */
    public String displayName() {
        if (nick != null && !nick.isBlank()) {
            return nick.trim();
        }
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.length() <= 1) {
            return "*";
        }
        if (trimmed.length() == 2) {
            return trimmed.charAt(0) + "*";
        }
        return trimmed.charAt(0) + "*".repeat(trimmed.length() - 2) + trimmed.charAt(trimmed.length() - 1);
    }
}
