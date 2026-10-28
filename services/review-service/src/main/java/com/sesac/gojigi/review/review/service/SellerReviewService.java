package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.common.util.TextSanitizer;
import com.sesac.gojigi.review.review.dto.request.ReviewReplyRequest;
import com.sesac.gojigi.review.review.dto.request.SellerReviewTab;
import com.sesac.gojigi.review.review.dto.response.ReviewReplyResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse.ProductRating;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse.TabCounts;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.exception.ReviewException;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 판매자 리뷰 관리 (REV-06, review.md 6 · 7절) — 내 스토어 리뷰 목록 · 평점 요약 · 답글 작성 · 수정. 담당 이경석.
 * 스토어는 게이트웨이가 넣는 X-Store-Id 만 본다. 다른 스토어 · 삭제된 리뷰는 없는 것으로(404).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SellerReviewService {

    /** 최신 작성순, 같은 시각이면 나중 ID 먼저 */
    private static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
    /** 1~2점 탭 기준 */
    private static final int LOW_RATING_MAX = 2;

    private final ReviewRepository reviewRepository;
    private final ReviewImages reviewImages;
    private final Clock clock;

    public PageResponse<SellerReviewResponse> list(Long storeId, SellerReviewTab tab, Long productId, int page, int size) {
        requireStore(storeId);
        return reviewImages.toPage(reviewRepository.findForSeller(storeId, productId, tab == SellerReviewTab.NO_REPLY,
                        tab == SellerReviewTab.LOW_RATING, tab == SellerReviewTab.PHOTO, PageRequest.of(page, size, NEWEST)),
                (review, images) -> SellerReviewResponse.from(review, ReviewImages.urls(images)));
    }

    public SellerReviewSummaryResponse summarize(Long storeId) {
        requireStore(storeId);
        RatingDistribution ratings = RatingDistribution.of(reviewRepository.countByRatingForStore(storeId));
        List<ProductRating> products = reviewRepository.summarizeByProductForStore(storeId).stream()
                .map(row -> new ProductRating((Long) row[0], (String) row[1], RatingDistribution.round2((Double) row[2]),
                        (Long) row[3]))
                .sorted(Comparator.comparingDouble(ProductRating::averageRating).reversed()
                        .thenComparing(ProductRating::productId))
                .toList();
        TabCounts tabCounts = new TabCounts(reviewRepository.countByStoreIdAndDeletedAtIsNull(storeId),
                reviewRepository.countByStoreIdAndDeletedAtIsNullAndReplyContentIsNullAndBlindedFalse(storeId),
                reviewRepository.countByStoreIdAndDeletedAtIsNullAndRatingLessThanEqual(storeId, LOW_RATING_MAX),
                reviewRepository.countWithPhotoForStore(storeId));

        return new SellerReviewSummaryResponse(ratings.average(), ratings.count(), ratings.distribution(), products,
                tabCounts);
    }

    /** 답글 작성 · 수정 — 리뷰당 1개라 덮어쓴다. 블라인드된 리뷰에는 달 수 없다(B안 요청서) */
    @Transactional
    public ReviewReplyResponse reply(Long storeId, Long reviewId, ReviewReplyRequest request) {
        requireStore(storeId);
        Review review = reviewRepository.findById(reviewId)
                .filter(r -> !r.isDeleted() && r.belongsToStore(storeId))
                .orElseThrow(() -> new ReviewException(ReviewErrorCode.REVIEW_NOT_FOUND));
        if (review.isBlinded()) {
            throw new ReviewException(ReviewErrorCode.REVIEW_BLINDED);
        }
        String content = TextSanitizer.plainText(request.content());
        if (content == null) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT, "답글을 입력해 주세요.");
        }

        boolean edited = review.hasReply();
        review.reply(content, LocalDateTime.now(clock));
        log.info("판매자 답글 {} reviewId={} storeId={}", edited ? "수정" : "작성", review.getId(), storeId);
        return ReviewReplyResponse.from(review);
    }

    /** 게이트웨이가 SELLER 를 확인해도 X-Store-Id 가 없을 수 있다(스토어 승인 전 토큰) — order 판매자 API 와 같은 403 */
    private static void requireStore(Long storeId) {
        if (storeId == null) {
            throw new BusinessException(CommonErrorCode.FORBIDDEN, "스토어 정보가 없습니다.");
        }
    }
}
