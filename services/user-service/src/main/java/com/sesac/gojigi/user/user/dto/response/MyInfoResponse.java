package com.sesac.gojigi.user.user.dto.response;

import com.sesac.gojigi.user.user.entity.User;
import com.sesac.gojigi.user.user.entity.status.Grade;
import com.sesac.gojigi.user.user.entity.status.SignupProvider;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 내 정보 — userdb 값만. signupProvider 가 LOCAL 이 아니면(소셜 가입) 비밀번호 변경 불가 · phone 이 비어 있을 수 있음.
 * TODO(이경석) orderCount — order-service 내부 API 가 생기면 추가
 */
public record MyInfoResponse(Long userId, String email, String name, String nick, String phone, Grade grade,
                             SignupProvider signupProvider, long point, long totalSpent, OffsetDateTime joinedAt) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static MyInfoResponse from(User user) {
        return new MyInfoResponse(user.getId(), user.getEmail(), user.getName(), user.getNick(), user.getPhone(),
                user.getGrade(), user.getSignupProvider(), user.getPointBalance(), user.getTotalConfirmedAmt(),
                user.getCreatedAt() == null ? null : user.getCreatedAt().atZone(KST).toOffsetDateTime());
    }
}
