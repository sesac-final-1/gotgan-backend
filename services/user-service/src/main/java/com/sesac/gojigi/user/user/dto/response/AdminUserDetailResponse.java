package com.sesac.gojigi.user.user.dto.response;

import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 관리자 회원 상세 — 목록 항목 + 연락처 · 상태 사유 · 정지 시각.
 * statusReason 은 마지막 정지 · 해제 때 관리자가 입력한 사유 (정상 회원도 해제 사유가 남아 있을 수 있음).
 */
public record AdminUserDetailResponse(Long userId, String email, String name, String nick, String phone, Grade grade,
                                      UserStatus status, String statusReason, OffsetDateTime suspendedAt,
                                      SignupProvider signupProvider, long point, long totalSpent,
                                      OffsetDateTime joinedAt, OffsetDateTime lastLoginAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static AdminUserDetailResponse from(User user) {
        return new AdminUserDetailResponse(user.getId(), user.getEmail(), user.getName(), user.getNick(),
                user.getPhone(), user.getGrade(), user.getStatus(), user.getStatusReason(), kst(user.getSuspendedAt()),
                user.getSignupProvider(), user.getPointBalance(), user.getTotalConfirmedAmt(),
                kst(user.getCreatedAt()), kst(user.getLastLoginAt()));
    }

    private static OffsetDateTime kst(LocalDateTime at) {
        return at == null ? null : at.atZone(KST).toOffsetDateTime();
    }
}
