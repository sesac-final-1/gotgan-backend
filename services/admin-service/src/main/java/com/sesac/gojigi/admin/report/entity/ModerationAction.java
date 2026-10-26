package com.sesac.gojigi.admin.report.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

/** 관리자 조치 이력 (INSERT 전용) — 처리 1번 = 1행. 대상 서비스 호출이 성공한 뒤에만 남긴다 */
@Getter
@Entity
@Table(name = "moderation_action")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ModerationAction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "moderation_action_id")
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", nullable = false, length = 20)
    private ReportTargetType targetType;

    @Column(name = "target_id", nullable = false)
    private Long targetId;

    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    private ModerationActionType action;

    @Column(name = "reason", nullable = false)
    private String reason;

    @Column(name = "suspend_days")
    private Integer suspendDays;

    @Column(name = "admin_id", nullable = false)
    private Long adminId;

    @Column(name = "report_count", nullable = false)
    private int reportCount;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", insertable = false, updatable = false, columnDefinition = "DATETIME(6)")
    private LocalDateTime createdAt;

    private ModerationAction(ReportTargetType targetType, Long targetId, ModerationActionType action, String reason,
            Integer suspendDays, Long adminId, int reportCount) {
        this.targetType = targetType;
        this.targetId = targetId;
        this.action = action;
        this.reason = reason;
        this.suspendDays = suspendDays;
        this.adminId = adminId;
        this.reportCount = reportCount;
    }

    public static ModerationAction of(ReportTargetType targetType, Long targetId, ModerationActionType action, String reason,
            Integer suspendDays, Long adminId, int reportCount) {
        return new ModerationAction(targetType, targetId, action, reason, suspendDays, adminId, reportCount);
    }
}
