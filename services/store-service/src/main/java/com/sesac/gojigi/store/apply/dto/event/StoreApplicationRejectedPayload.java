package com.sesac.gojigi.store.apply.dto.event;

import java.time.OffsetDateTime;

/**
 * StoreApplicationRejected payload (config docs/events.md · store.md 6절). 알림 Lambda(반려 메일) · admin 알림 센터가 이 이름 그대로 읽는다.
 * 필드는 추가만 — 삭제 · 이름 변경 금지.
 */
public record StoreApplicationRejectedPayload(
        Long applicationId,
        Long userId,              // 신청 때의 X-User-Id — admin 알림 센터 USER#{userId} · 알림 이력 키
        String storeName,
        String sellerEmail,       // 신청서 managerEmail — 반려 메일 수신자
        String reason,
        OffsetDateTime decidedAt) { // 알림 Lambda 가 +30일로 재신청 가능일을 계산
}
