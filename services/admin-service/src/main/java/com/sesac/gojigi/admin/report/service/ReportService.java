package com.sesac.gojigi.admin.report.service;

import com.sesac.gojigi.admin.report.dto.request.ReportCreateRequest;
import com.sesac.gojigi.admin.report.dto.response.ReportResponses;
import com.sesac.gojigi.admin.report.entity.Report;
import com.sesac.gojigi.admin.report.entity.ReportReason;
import com.sesac.gojigi.admin.report.entity.ReportTargetType;
import com.sesac.gojigi.admin.report.exception.ReportErrorCode;
import com.sesac.gojigi.admin.report.exception.ReportException;
import com.sesac.gojigi.admin.report.repository.ReportRepository;
import com.sesac.gojigi.admin.review.client.ReviewReader;
import com.sesac.gojigi.admin.user.client.UserClientAdapter;
import com.sesac.gojigi.common.web.auth.AuthUser;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * 신고 접수 (구매자 · 판매자) — 대상이 있는지 대상 서비스에 물어 확인하고, 신고 당시 요약을 남긴다.
 * 대상 조회는 트랜잭션 밖 (다른 서비스 호출 동안 DB 커넥션을 잡지 않음)
 */
@Service
@RequiredArgsConstructor
public class ReportService {

    static final int SUMMARY_MAX = 200;

    private final ReportRepository reportRepository;
    private final ReviewReader reviewReader;
    private final UserClientAdapter userClientAdapter;

    public ReportResponses.Created create(AuthUser user, ReportCreateRequest request) {
        boolean seller = user.hasRole("SELLER");
        ReportTargetType type = request.targetType();
        ReportReason reason = request.reasonCode();
        if (!reason.allows(type) || (reason == ReportReason.SELLER_REQUEST && !seller)) {
            throw new ReportException(ReportErrorCode.REPORT_REASON_NOT_ALLOWED);
        }
        if (type == ReportTargetType.USER && request.targetId().equals(user.userId())) {
            throw new ReportException(ReportErrorCode.REPORT_SELF);
        }
        if (reportRepository.existsByTargetTypeAndTargetIdAndReporterId(type, request.targetId(), user.userId())) {
            throw new ReportException(ReportErrorCode.REPORT_DUPLICATED);
        }
        String summary = type == ReportTargetType.REVIEW ? reviewSummary(user, request) : userSummary(user, request.targetId());
        try {
            Report saved = reportRepository.save(Report.open(type, request.targetId(), user.userId(), seller ? "SELLER" : "BUYER",
                    reason, blankToNull(request.detail()), summary));
            return new ReportResponses.Created(saved.getId(), saved.getStatus());
        } catch (DataIntegrityViolationException e) {
            throw new ReportException(ReportErrorCode.REPORT_DUPLICATED);   // 같은 사람이 동시에 두 번 누름
        }
    }

    /** 리뷰가 있는지(없으면 review 의 404 그대로) · 내 리뷰가 아닌지 · 판매자 검토 요청은 내 스토어 리뷰인지 */
    private String reviewSummary(AuthUser user, ReportCreateRequest request) {
        JsonNode review = reviewReader.get(request.targetId());
        if (review.hasNonNull("userId") && review.get("userId").asLong() == user.userId()) {
            throw new ReportException(ReportErrorCode.REPORT_SELF);
        }
        if (request.reasonCode() == ReportReason.SELLER_REQUEST && review.hasNonNull("storeId")
                && !Long.valueOf(review.get("storeId").asLong()).equals(user.storeId())) {
            throw new ReportException(ReportErrorCode.REPORT_NOT_OWN_STORE);
        }
        return cut(review.path("content").asString(""));
    }

    /** 회원이 있는지(없으면 user 의 404 그대로) — 요약은 이름 · 닉네임 */
    private String userSummary(AuthUser user, Long targetId) {
        JsonNode target = userClientAdapter.detail(user.userId(), targetId);
        String nick = target.path("nick").asString("");
        return cut(target.path("name").asString("") + (nick.isEmpty() ? "" : " (" + nick + ")"));
    }

    static String cut(String text) {
        return text.length() <= SUMMARY_MAX ? text : text.substring(0, SUMMARY_MAX) + "…";
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
