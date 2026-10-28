package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.common.util.TextSanitizer;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewCreateResponse;
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
 * 리뷰 작성 (REV-02, docs/api/review.md 1절). 담당 이경석.
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
        String content = TextSanitizer.plainText(request.content());
        if (content == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "리뷰 내용을 입력해 주세요.");
        }

        Review review = Review.write(eligibility, request.rating(), TextSanitizer.plainText(request.title()),
                content, request.fit());
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
}
