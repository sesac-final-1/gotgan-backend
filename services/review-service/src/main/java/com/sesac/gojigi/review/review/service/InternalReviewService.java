package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.review.dto.request.ReviewBlindRequest;
import com.sesac.gojigi.review.review.dto.response.InternalReviewResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * admin-service 신고 처리용 내부 API (review.md 8절, 노션 「신고 · 리뷰 블라인드 (B안)」 4-2). 담당 이경석.
 * 블라인드 상태의 주인은 review, 신고 · 조치 이력은 admindb. 삭제된 리뷰는 없는 것으로(404).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InternalReviewService {

    private final ReviewRepository reviewRepository;
    private final Clock clock;

    public InternalReviewResponse get(Long reviewId) {
        return InternalReviewResponse.from(findNotDeleted(reviewId));
    }

    /** 블라인드 · 해제 — 같은 상태 재요청은 바꾸지 않고 200 (관리자가 두 번 눌러도 안전, 처음 사유 · 시각 유지) */
    @Transactional
    public InternalReviewResponse changeBlind(Long reviewId, ReviewBlindRequest request) {
        Review review = findNotDeleted(reviewId);
        boolean before = review.isBlinded();
        if (request.blinded()) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new BusinessException(CommonErrorCode.INVALID_INPUT, "블라인드 사유를 입력해 주세요.");
            }
            review.blind(request.reason().strip(), LocalDateTime.now(clock));
        } else {
            review.unblind();
        }
        if (before != review.isBlinded()) {
            log.info("리뷰 블라인드 {} reviewId={} storeId={} reason={}", review.isBlinded() ? "처리" : "해제",
                    review.getId(), review.getStoreId(), review.getBlindReason());
        }
        return InternalReviewResponse.from(review);
    }

    private Review findNotDeleted(Long reviewId) {
        return reviewRepository.findById(reviewId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));
    }
}
