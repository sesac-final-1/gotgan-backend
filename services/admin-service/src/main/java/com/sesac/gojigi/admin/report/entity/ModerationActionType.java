package com.sesac.gojigi.admin.report.entity;

/**
 * 관리자 조치 — 대상에 맞는 것만 (리뷰: 블라인드 · 해제, 회원: 정지 · 해제, 공통: 기각).
 * 블라인드 · 정지는 열린 신고를 RESOLVED, 해제 · 기각은 DISMISSED 로 닫는다 (신고가 맞지 않았다고 본 것)
 */
public enum ModerationActionType {
    BLIND(ReportTargetType.REVIEW, ReportStatus.RESOLVED),
    UNBLIND(ReportTargetType.REVIEW, ReportStatus.DISMISSED),
    SUSPEND(ReportTargetType.USER, ReportStatus.RESOLVED),
    UNSUSPEND(ReportTargetType.USER, ReportStatus.DISMISSED),
    DISMISS(null, ReportStatus.DISMISSED);

    private final ReportTargetType target;   // null = 모든 대상
    private final ReportStatus closesAs;

    ModerationActionType(ReportTargetType target, ReportStatus closesAs) {
        this.target = target;
        this.closesAs = closesAs;
    }

    public boolean appliesTo(ReportTargetType targetType) {
        return target == null || target == targetType;
    }

    public ReportStatus closesAs() {
        return closesAs;
    }
}
