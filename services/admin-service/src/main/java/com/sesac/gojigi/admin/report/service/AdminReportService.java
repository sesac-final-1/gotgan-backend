package com.sesac.gojigi.admin.report.service;

import com.sesac.gojigi.admin.report.dto.request.ReportResolveRequest;
import com.sesac.gojigi.admin.report.dto.response.ReportResponses;
import com.sesac.gojigi.admin.report.entity.ModerationAction;
import com.sesac.gojigi.admin.report.entity.ModerationActionType;
import com.sesac.gojigi.admin.report.entity.Report;
import com.sesac.gojigi.admin.report.entity.ReportReason;
import com.sesac.gojigi.admin.report.entity.ReportStatus;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import com.sesac.gojigi.admin.report.exception.ReportErrorCode;
import com.sesac.gojigi.admin.report.exception.ReportException;
import com.sesac.gojigi.admin.report.repository.ModerationActionRepository;
import com.sesac.gojigi.admin.report.repository.ReportRepository;
import com.sesac.gojigi.admin.review.client.ReviewReader;
import com.sesac.gojigi.admin.user.client.UserClientAdapter;
import com.sesac.gojigi.common.web.exception.BusinessException;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 관리자 신고 처리 — 목록은 대상별로 묶고, 처리는 대상 서비스(review 블라인드 · user 정지)를 먼저 부른 뒤 성공하면 기록한다.
 * 대상 서비스 호출이 실패하면 아무것도 남기지 않는다 (신고는 그대로 OPEN — 다시 처리하면 됨)
 */
@Service
@RequiredArgsConstructor
public class AdminReportService {

    private final ReportRepository reportRepository;
    private final ModerationActionRepository moderationActionRepository;
    private final ReportTxService reportTx;
    private final ReviewReader reviewReader;
    private final UserClientAdapter userClientAdapter;
    private final JsonMapper jsonMapper;

    private record Key(ReportTargetType type, Long id) {
    }

    /** 대상별 묶음 — 처리 대기 많은 순 → 최근 신고 순. targetType 생략 = 전체 */
    public List<ReportResponses.TargetSummary> list(ReportTargetType targetType) {
        List<Report> reports = targetType == null ? reportRepository.findAllByOrderByIdDesc()
                : reportRepository.findByTargetTypeOrderByIdDesc(targetType);
        Map<Key, ModerationAction> lastActions = moderationActionRepository.findAllByOrderByIdDesc().stream()
                .collect(Collectors.toMap(a -> new Key(a.getTargetType(), a.getTargetId()), Function.identity(), (first, older) -> first));
        Map<Key, List<Report>> byTarget = reports.stream()
                .collect(Collectors.groupingBy(r -> new Key(r.getTargetType(), r.getTargetId()), LinkedHashMap::new, Collectors.toList()));
        return byTarget.entrySet().stream()
                .map(e -> summary(e.getKey(), e.getValue(), lastActions.get(e.getKey())))
                .sorted(Comparator.comparingLong(ReportResponses.TargetSummary::openCount).reversed()
                        .thenComparing(ReportResponses.TargetSummary::lastReportedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private static ReportResponses.TargetSummary summary(Key key, List<Report> reports, ModerationAction lastAction) {
        Report latest = reports.getFirst();   // id 내림차순
        Map<ReportReason, Long> reasons = reports.stream()
                .collect(Collectors.groupingBy(Report::getReasonCode, () -> new EnumMap<>(ReportReason.class), Collectors.counting()));
        long open = reports.stream().filter(r -> r.getStatus() == ReportStatus.OPEN).count();
        return new ReportResponses.TargetSummary(key.type(), key.id(), latest.getTargetSummary(), open, reports.size(), reasons,
                ReportResponses.ReportItem.of(latest).createdAt(), ReportResponses.ActionItem.of(lastAction));
    }

    /** 상세 — 신고 목록 · 조치 이력 + 대상 서비스의 현재 값 (불러오지 못해도 신고 기록은 보여 준다) */
    public ReportResponses.TargetDetail detail(ReportTargetType type, Long targetId, Long adminId) {
        JsonNode target = null;
        String targetError = null;
        try {
            target = type == ReportTargetType.REVIEW ? reviewReader.get(targetId) : userClientAdapter.detail(adminId, targetId);
        } catch (BusinessException e) {
            targetError = e.getMessage();
        }
        return new ReportResponses.TargetDetail(type, targetId, target, targetError,
                reportRepository.findByTargetTypeAndTargetIdOrderByIdDesc(type, targetId).stream().map(ReportResponses.ReportItem::of).toList(),
                moderationActionRepository.findByTargetTypeAndTargetIdOrderByIdDesc(type, targetId).stream()
                        .map(ReportResponses.ActionItem::of).toList());
    }

    /** 처리 — 리뷰: 블라인드 · 해제 / 회원: 정지 · 해제 / 공통: 기각. 대상 서비스 → 기록 순서 */
    public ReportResponses.Resolved resolve(ReportTargetType type, Long targetId, Long adminId, ReportResolveRequest request) {
        ModerationActionType action = request.action();
        if (!action.appliesTo(type) || (request.days() != null && action != ModerationActionType.SUSPEND)) {
            throw new ReportException(ReportErrorCode.REPORT_ACTION_NOT_ALLOWED);
        }
        String reason = request.reason().strip();
        JsonNode target = switch (action) {
            case BLIND -> reviewReader.blind(targetId, true, reason);
            case UNBLIND -> reviewReader.blind(targetId, false, reason);
            case SUSPEND -> userClientAdapter.changeStatus(adminId, targetId, userStatus("SUSPENDED", reason, request.days()));
            case UNSUSPEND -> userClientAdapter.changeStatus(adminId, targetId, userStatus("ACTIVE", reason, null));
            case DISMISS -> null;
        };
        ReportTxService.Recorded recorded = reportTx.record(type, targetId, action, reason, request.days(), adminId);
        return new ReportResponses.Resolved(ReportResponses.ActionItem.of(recorded.action()), recorded.closedReports(), target);
    }

    /** user PATCH /internal/users/{id}/status 본문 — days 생략 = 영구 (관리자 회원 화면과 같은 규칙) */
    private JsonNode userStatus(String status, String reason, Integer days) {
        ObjectNode body = jsonMapper.createObjectNode().put("status", status).put("reason", reason);
        return days == null ? body : body.put("days", days);
    }
}
