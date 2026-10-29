package com.sesac.gojigi.store.apply.service;

import com.sesac.gojigi.store.apply.entity.StoreApplication;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 보완 기한 (STR-09, store.md 7절) — 보완요청 시각 + 기한. 운영 7일, 시연 때만 APPLY_SUPPLEMENT_DUE 로 짧게(order 자동 배송완료와 같은 방식).
 * 보완요청 이벤트 dueAt · 내 신청 supplementDueAt · 재제출 검사 · 자동 반려가 모두 이 값 하나를 쓴다.
 */
@Component
public class SupplementDeadline {

    private final Duration due;

    public SupplementDeadline(@Value("${app.apply.supplement-due:7d}") Duration due) {
        this.due = due;
    }

    public Duration due() {
        return due;
    }

    public LocalDateTime dueAt(StoreApplication application) {
        return application.supplementDueAt(due);
    }

    /** 이 시각 이전(포함)에 보완요청된 신청은 기한이 지났다 — 자동 반려 대상 */
    public LocalDateTime requestedBeforeExpired(LocalDateTime now) {
        return now.minus(due);
    }
}
