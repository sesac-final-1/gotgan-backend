package com.sesac.gojigi.admin.notification.dto;

import java.util.List;

/** 알림 목록 — 최신순, nextCursor 가 있으면 ?cursor= 로 이전 알림. unreadCount = 안 읽은 알림 전체 수 */
public record NotificationPage(List<NotificationResponse> items, String nextCursor, long unreadCount) {
}
