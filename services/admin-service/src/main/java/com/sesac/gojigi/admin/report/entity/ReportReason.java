package com.sesac.gojigi.admin.report.entity;

import java.util.EnumSet;
import java.util.Set;

/** 신고 사유 — 대상마다 고를 수 있는 사유가 다르다. SELLER_REQUEST 는 판매자의 "관리자 검토 요청"(내 스토어 리뷰만) */
public enum ReportReason {
    SPAM(EnumSet.of(ReportTargetType.REVIEW, ReportTargetType.USER)),          // 광고 · 도배
    ABUSE(EnumSet.of(ReportTargetType.REVIEW, ReportTargetType.USER)),         // 욕설 · 비방
    FALSE_INFO(EnumSet.of(ReportTargetType.REVIEW)),                           // 허위 · 구매와 무관한 리뷰
    PRIVACY(EnumSet.of(ReportTargetType.REVIEW)),                              // 개인정보 노출
    OBSCENE(EnumSet.of(ReportTargetType.REVIEW)),                              // 음란 · 부적절한 사진
    FRAUD(EnumSet.of(ReportTargetType.USER)),                                  // 거래 사기 · 결제 도용 의심
    SELLER_REQUEST(EnumSet.of(ReportTargetType.REVIEW)),                       // 판매자 검토 요청
    ETC(EnumSet.of(ReportTargetType.REVIEW, ReportTargetType.USER));

    private final Set<ReportTargetType> targets;

    ReportReason(Set<ReportTargetType> targets) {
        this.targets = targets;
    }

    public boolean allows(ReportTargetType targetType) {
        return targets.contains(targetType);
    }
}
