package com.sesac.gojigi.admin.notification.dto;

import java.time.OffsetDateTime;

/** 알림 하나 (화면) — id = 알림함 키(받는 쪽 + 정렬 키)를 URL 에 쓸 수 있게 감싼 값. 읽음 처리에 그대로 돌려준다 */
public record NotificationResponse(String id, String type, String title, String body, String link, boolean read, OffsetDateTime createdAt) {
}
