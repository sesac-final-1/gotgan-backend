package com.sesac.gojigi.admin.report.dto.response;

import com.sesac.gojigi.admin.report.entity.ModerationAction;
import com.sesac.gojigi.admin.report.entity.ModerationActionType;
import com.sesac.gojigi.admin.report.entity.Report;
import com.sesac.gojigi.admin.report.entity.ReportReason;
import com.sesac.gojigi.admin.report.entity.ReportStatus;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;

/** 신고 API 응답 (docs/api/admin.md 신고 절) — 시각은 KST 오프셋 */
public final class ReportResponses {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private ReportResponses() {
    }

    static OffsetDateTime kst(LocalDateTime at) {
        return at == null ? null : at.atZone(KST).toOffsetDateTime();
    }

    /** POST /api/reports */
    public record Created(Long reportId, ReportStatus status) {
    }

    /** 관리자 목록 — 대상 1개 = 1행. openCount 큰 순 → 최근 신고 순 */
    public record TargetSummary(ReportTargetType targetType, Long targetId, String summary, long openCount, long totalCount,
            Map<ReportReason, Long> reasonCounts, OffsetDateTime lastReportedAt, ActionItem lastAction) {
    }

    /** 관리자 상세 — target 은 대상 서비스 현재 값(불러오지 못하면 null + targetError) */
    public record TargetDetail(ReportTargetType targetType, Long targetId, JsonNode target, String targetError,
            List<ReportItem> reports, List<ActionItem> actions) {
    }

    public record ReportItem(Long reportId, Long reporterId, String reporterRole, ReportReason reasonCode, String detail,
            String targetSummary, ReportStatus status, OffsetDateTime createdAt) {

        public static ReportItem of(Report r) {
            return new ReportItem(r.getId(), r.getReporterId(), r.getReporterRole(), r.getReasonCode(), r.getDetail(),
                    r.getTargetSummary(), r.getStatus(), kst(r.getCreatedAt()));
        }
    }

    public record ActionItem(Long moderationActionId, ModerationActionType action, String reason, Integer suspendDays,
            Long adminId, int reportCount, OffsetDateTime createdAt) {

        public static ActionItem of(ModerationAction a) {
            return a == null ? null : new ActionItem(a.getId(), a.getAction(), a.getReason(), a.getSuspendDays(), a.getAdminId(),
                    a.getReportCount(), kst(a.getCreatedAt()));
        }
    }

    /** POST .../resolve — 닫은 신고 수 + 대상 서비스 응답(기각이면 null) */
    public record Resolved(ActionItem action, int closedReports, JsonNode target) {
    }
}
