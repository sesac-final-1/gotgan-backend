package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.review.dto.response.ReviewImageResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewImage;
import com.sesac.gojigi.review.review.repository.ReviewImageRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;

/**
 * 리뷰 사진 — 저장(작성 · 수정 때 통째로 교체)과 조회. URL = IMAGES_BASE_URL/{key} (product ImageUrls 와 같음).
 * 목록은 리뷰 ID 묶음으로 한 번에 읽어 N+1 을 피한다. 순서(sort_order)대로, 사진 없는 리뷰는 빈 목록.
 */
@Component
public class ReviewImages {

    private final ReviewImageRepository reviewImageRepository;
    private final String baseUrl;

    public ReviewImages(ReviewImageRepository reviewImageRepository, @Value("${app.images.base-url}") String baseUrl) {
        this.reviewImageRepository = reviewImageRepository;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** 사진을 통째로 바꾼다 — 지우고 순서대로 다시 넣는다 (uk_review_image_review_sort 때문에 삭제를 먼저 flush) */
    public void replace(Review review, List<String> keys) {
        reviewImageRepository.deleteByReview(review);
        for (int i = 0; i < keys.size(); i++) {
            reviewImageRepository.save(new ReviewImage(review, keys.get(i), i));
        }
    }

    /** 리뷰 페이지 → 응답 페이지, 사진은 페이지 단위로 한 번에 읽어 붙인다 */
    public <T> PageResponse<T> toPage(Page<Review> reviews, BiFunction<Review, List<ReviewImageResponse>, T> toResponse) {
        Map<Long, List<ReviewImageResponse>> images = byReviewId(reviews.getContent().stream().map(Review::getId).toList());
        return PageResponse.from(reviews.map(review -> toResponse.apply(review, images.getOrDefault(review.getId(), List.of()))));
    }

    public List<ReviewImageResponse> of(Long reviewId) {
        return byReviewId(List.of(reviewId)).getOrDefault(reviewId, List.of());
    }

    /** 공개 · 판매자 · 관리자 응답은 URL 만 */
    public static List<String> urls(List<ReviewImageResponse> images) {
        return images.stream().map(ReviewImageResponse::url).toList();
    }

    private Map<Long, List<ReviewImageResponse>> byReviewId(Collection<Long> reviewIds) {
        if (reviewIds.isEmpty()) {
            return Map.of();
        }
        return reviewImageRepository.findByReviewIdsOrdered(reviewIds).stream()
                .collect(Collectors.groupingBy(image -> image.getReview().getId(),
                        Collectors.mapping(image -> new ReviewImageResponse(image.getImageKey(), baseUrl + "/" + image.getImageKey()),
                                Collectors.toList())));
    }
}
