package com.sesac.gojigi.admin.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 다른 서비스가 보내는 알림 (POST /internal/notifications) — 받는 쪽은 userIds · storeIds · admins 중 하나 이상.
 * sourceId 를 주면 같은 값은 한 번만 쌓인다(재시도 멱등), 생략하면 매번 새 알림
 */
public record InternalNotificationRequest(
        @Schema(description = "받는 회원") List<Long> userIds,
        @Schema(description = "받는 스토어(판매자)") List<Long> storeIds,
        @Schema(description = "관리자 전원 (생략 = false)") Boolean admins,
        @Schema(description = "종류 — 화면 아이콘 · 분류", example = "CHAT_MESSAGE") @NotBlank @Size(max = 40) String type,
        @NotBlank @Size(max = 100) String title,
        @Size(max = 300) String body,
        @Schema(description = "누르면 이동할 화면 경로", example = "/mypage/chats/12") @Size(max = 200) String link,
        @Schema(description = "같은 알림 판별 값 (생략 = 매번 새 알림)") @Size(max = 100) String sourceId) {
}
