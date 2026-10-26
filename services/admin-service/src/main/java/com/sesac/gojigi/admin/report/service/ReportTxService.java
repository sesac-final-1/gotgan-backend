package com.sesac.gojigi.admin.report.service;

import com.sesac.gojigi.admin.report.entity.ModerationAction;
import com.sesac.gojigi.admin.report.entity.ModerationActionType;
import com.sesac.gojigi.admin.report.entity.Report;
import com.sesac.gojigi.admin.report.entity.ReportStatus;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import com.sesac.gojigi.admin.report.repository.ModerationActionRepository;
import com.sesac.gojigi.admin.report.repository.ReportRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 조치 기록 — 대상 서비스 호출이 성공한 뒤 짧은 트랜잭션으로 조치 1행 + 열린 신고 닫기 */
@Service
@RequiredArgsConstructor
public class ReportTxService {

    private final ReportRepository reportRepository;
    private final ModerationActionRepository moderationActionRepository;

    public record Recorded(ModerationAction action, int closedReports) {
    }

    @Transactional
    public Recorded record(ReportTargetType type, Long targetId, ModerationActionType actionType, String reason, Integer days,
            Long adminId) {
        List<Report> open = reportRepository.findByTargetTypeAndTargetIdAndStatus(type, targetId, ReportStatus.OPEN);
        ModerationAction action = moderationActionRepository.saveAndFlush(
                ModerationAction.of(type, targetId, actionType, reason, days, adminId, open.size()));
        open.forEach(r -> r.close(actionType.closesAs(), action.getId()));
        return new Recorded(action, open.size());
    }
}
