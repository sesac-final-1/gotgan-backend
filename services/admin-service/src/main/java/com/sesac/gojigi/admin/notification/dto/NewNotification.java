package com.sesac.gojigi.admin.notification.dto;

import java.time.Instant;

/**
 * 쌓을 알림 하나 — recipient = 알림함 PK(USER#… · STORE#… · ADMIN), sourceId = 같은 알림 판별용(이벤트면 eventId).
 * 알림함 SK 는 {발생 시각 epoch ms 13자리}#{sourceId} — 시간순 정렬 · 재전달 멱등
 */
public record NewNotification(String recipient, String sourceId, String type, String title, String body, String link, Instant occurredAt) {

    public String sortKey() {
        return String.format("%013d#%s", occurredAt.toEpochMilli(), sourceId);
    }
}
