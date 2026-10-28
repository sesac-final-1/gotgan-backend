package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.common.util.TextSanitizer;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 리뷰 작성 · 수정 · 삭제 (REV-02 · 05, docs/api/review.md 1 ~ 3절). 담당 이경석.
 * 자격은 review_eligibility(OrderItemConfirmed 로 저장)만 본다 — order 를 호출하지 않는다.
 * 검사 순서: 자격(없음 · 남의 상품주문) → 이미 작성 → 기한 30일.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ReviewEligibilityRepository eligibilityRepository;
    private final ReviewRepository reviewRepository;
    private final Clock clock;

    @Transactional
    public ReviewCreateResponse write(Long userId, ReviewCreateRequest request) {
        // 없는 상품주문 · 남의 상품주문은 같은 응답 — 다른 회원의 구매 여부를 드러내지 않는다
        ReviewEligibility eligibility = eligibilityRepository.findByOrderItemId(request.orderItemId())
                .filter(e -> e.isOwnedBy(userId))
                .orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_ELIGIBLE));
        if (reviewRepository.existsByOrderItemId(eligibility.getOrderItemId())) {
            throw new ReviewException(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
        }
        if (eligibility.isExpired(LocalDateTime.now(clock))) {
            throw new ReviewException(ReviewErrorCode.REVIEW_WRITE_EXPIRED);
        }
        Review review = Review.write(eligibility, request.rating(), TextSanitizer.plainText(request.title()),
                sanitizeContent(request.content()), request.fit());
        try {
            reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // 같은 상품주문을 동시에 두 번 제출 — uk_review_order_item 이 한쪽만 남긴다
            throw new ReviewException(ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
        }
        log.info("리뷰 작성 reviewId={} userId={} orderItemId={} productId={} rating={}",
                review.getId(), userId, review.getOrderItemId(), review.getProductId(), review.getRating());
        return new ReviewCreateResponse(review.getId());
    }

    /**
     * 내 리뷰 수정 (REV-05, review.md 2절) — 기한 없이 본인만. 없음 · 삭제됨 · 남의 리뷰는 같은 404.
     * 블라인드된 리뷰는 수정 불가 — 블라인드 사유가 된 내용을 고쳐 노출을 되살리는 것을 막는다(해제는 관리자만).
     */
    @Transactional
    public ReviewResponse edit(Long userId, Long reviewId, ReviewUpdateRequest request) {
        Review review = findOwnReview(userId, reviewId);
        if (review.isBlinded()) {
            throw new ReviewException(ReviewErrorCode.REVIEW_BLINDED);
        }

        review.edit(request.rating(), TextSanitizer.plainText(request.title()), sanitizeContent(request.content()),
                request.fit());
        log.info("리뷰 수정 reviewId={} userId={} rating={}", review.getId(), userId, review.getRating());
        return ReviewResponse.from(review);
    }

    /**
     * 내 리뷰 삭제 (REV-05, review.md 3절) — soft delete, 평균 · 리뷰 수에서 빠진다. 블라인드된 리뷰도 지울 수 있다.
     * 상품주문당 1개(uk)라 지운 뒤 같은 상품주문에 다시 쓸 수 없다.
     */
    @Transactional
    public void delete(Long userId, Long reviewId) {
        Review review = findOwnReview(userId, reviewId);
        review.softDelete(LocalDateTime.now(clock));
        log.info("리뷰 삭제 reviewId={} userId={} orderItemId={}", review.getId(), userId, review.getOrderItemId());
    }

    /** 없음 · 삭제됨 · 남의 리뷰는 같은 404 — 다른 회원 리뷰의 존재를 드러내지 않는다 */
    private Review findOwnReview(Long userId, Long reviewId) {
        return reviewRepository.findById(reviewId)
                .filter(r -> !r.isDeleted() && r.isOwnedBy(userId))
                .orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));
    }

    /** 태그를 지운 본문 — 남는 글자가 없으면 형식 오류 */
    private static String sanitizeContent(String content) {
        String sanitized = TextSanitizer.plainText(content);
        if (sanitized == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "리뷰 내용을 입력해 주세요.");
        }
        return sanitized;
    }
}
