package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse.FitCount;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 상세 공개 리뷰 (REV-04, review.md 9절) — 목록 · 요약. 담당 이경석.
 * 블라인드 · 삭제 리뷰는 없는 것으로. review 는 상품을 모르므로 없는 상품은 빈 목록 · 0 으로 답한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductReviewQueryService {

    private final ReviewRepository reviewRepository;
    private final ReviewImages reviewImages;

    public PageResponse<ProductReviewResponse> list(Long productId, Integer rating, boolean photoOnly,
                                                    ProductReviewSort sort, int page, int size) {
        return reviewImages.toPage(reviewRepository.findForProduct(productId, rating, photoOnly,
                        PageRequest.of(page, size, sort.toSort())),
                (review, images) -> ProductReviewResponse.from(review, ReviewImages.urls(images)));
    }

    public ProductReviewSummaryResponse summarize(Long productId) {
        RatingDistribution ratings = RatingDistribution.of(reviewRepository.countByRatingForProduct(productId));
        Map<ReviewFit, Long> countByFit = new EnumMap<>(ReviewFit.class);
        for (Object[] row : reviewRepository.countByFitForProduct(productId)) {
            countByFit.put((ReviewFit) row[0], (Long) row[1]);
        }
        List<FitCount> fits = Arrays.stream(ReviewFit.values())
                .map(fit -> new FitCount(fit, countByFit.getOrDefault(fit, 0L)))
                .toList();
        return new ProductReviewSummaryResponse(ratings.average(), ratings.count(), ratings.distribution(), fits);
    }
}
