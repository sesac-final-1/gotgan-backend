package com.sesac.gojigi.user.user.dto.response;

import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import com.sesac.gojigi.user.user.entity.status.UserStatus;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 관리자 회원 목록 한 줄 — userdb 값만. point = 포인트 잔액, totalSpent = 누적 구매확정 금액(등급 기준).
 * 주문 수는 order-service 값이라 여기 없음 (필요하면 admin-service 가 합친다).
 */
public record AdminUserSummaryResponse(Long userId, String email, String name, String nick, Grade grade,
                                       UserStatus status, SignupProvider signupProvider, long point, long totalSpent,
                                       OffsetDateTime joinedAt, OffsetDateTime lastLoginAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static AdminUserSummaryResponse from(User user) {
        return new AdminUserSummaryResponse(user.getId(), user.getEmail(), user.getName(), user.getNick(),
                user.getGrade(), user.getStatus(), user.getSignupProvider(), user.getPointBalance(),
                user.getTotalConfirmedAmt(), kst(user.getCreatedAt()), kst(user.getLastLoginAt()));
    }

    private static OffsetDateTime kst(LocalDateTime at) {
        return at == null ? null : at.atZone(KST).toOffsetDateTime();
    }
}
