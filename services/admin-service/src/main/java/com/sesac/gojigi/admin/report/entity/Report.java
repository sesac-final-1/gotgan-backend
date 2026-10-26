package com.sesac.gojigi.admin.report.entity;

import com.sesac.gojigi.admin.common.entity.BaseTimeEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 회원 · 리뷰 신고 — 신고자 · 대상당 1건(uk_report_target_reporter). OPEN 만 처리 대상 */
@Getter
@Entity
@Table(name = "report")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Report extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "report_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Column(name = "reporter_id", nullable = false)
    private Long reporterId;

    @Column(name = "reporter_role", nullable = false, length = 20)
    private String reporterRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReportReason reasonCode;

    @Column(name = "detail", length = 500)
    private String detail;

    @Column(name = "target_summary", length = 500)
    private String targetSummary;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ReportStatus status;

    @Column(name = "moderation_action_id")
    private Long moderationActionId;

    private Report(ReportTargetType targetType, Long targetId, Long reporterId, String reporterRole, ReportReason reasonCode,
            String detail, String targetSummary) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.reporterId = reporterId;
        this.reporterRole = reporterRole;
        this.reasonCode = reasonCode;
        this.detail = detail;
        this.targetSummary = targetSummary;
        this.status = ReportStatus.OPEN;
    }

    public static Report open(ReportTargetType targetType, Long targetId, Long reporterId, String reporterRole,
            ReportReason reasonCode, String detail, String targetSummary) {
        return new Report(targetType, targetId, reporterId, reporterRole, reasonCode, detail, targetSummary);
    }

    /** 관리자 조치로 닫힘 — OPEN 만 (이미 닫힌 신고는 그대로) */
    public void close(ReportStatus closedAs, Long moderationActionId) {
        if (status == ReportStatus.OPEN) {
            this.status = closedAs;
            this.moderationActionId = moderationActionId;
        }
    }
}
