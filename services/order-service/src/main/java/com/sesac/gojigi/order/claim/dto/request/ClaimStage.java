package com.sesac.gojigi.order.claim.dto.request;

import com.sesac.gojigi.order.claim.entity.ClaimStatus;
import java.util.Set;

/** 판매자 클레임 탭 — 처리대기(진행 중) · 처리완료(완료 · 거부) */
public enum ClaimStage {
    OPEN(ClaimStatus.OPEN),
    DONE(ClaimStatus.DONE);

    private final Set<ClaimStatus> statuses;

    ClaimStage(Set<ClaimStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<ClaimStatus> statuses() {
        return statuses;
    }

    public static ClaimStage of(ClaimStatus status) {
        return ClaimStatus.OPEN.contains(status) ? OPEN : DONE;
    }
}
