package com.sesac.gojigi.store.apply.dto.event;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * StoreApplicationSupplementRequested payload (config docs/events.md · store.md 7절). 알림 Lambda(보완요청 메일) · admin 알림 센터가 이 이름 그대로 읽는다.
 * 필드는 추가만 — 삭제 · 이름 변경 금지.
 */
public record StoreApplicationSupplementRequestedPayload(
        Long applicationId,
        Long userId,                  // 신청 때의 X-User-Id — admin 알림 센터 USER#{userId} · 알림 이력 키
        String storeName,
        String sellerEmail,           // 신청서 managerEmail — 보완요청 메일 수신자
        List<String> requestedDocs,   // 메일에 그대로 찍히는 한글 이름(DocType.label) — 요청 본문의 enum 과 다름
        String message,
        OffsetDateTime dueAt) {       // 보완요청 시각 + 7일, 메일의 "○○까지 보완하지 않으면 자동 반려"
}
