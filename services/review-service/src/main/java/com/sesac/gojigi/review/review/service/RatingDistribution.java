package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.review.review.dto.response.RatingCount;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

/**
 * 별점 분포 · 리뷰 수 · 평균 — 판매자 평점 요약(스토어) · 상품 리뷰 요약(상품) 공용.
 * 입력은 [rating, count] 집계 행, 분포는 항상 5 → 1점 5칸(없는 별점은 0), 평균은 소수 둘째 자리 반올림(리뷰 없으면 0).
 */
record RatingDistribution(List<RatingCount> distribution, long count, double average) {

    static RatingDistribution of(List<Object[]> countByRatingRows) {
        Map<Integer, Long> countByRating = new HashMap<>();
        for (Object[] row : countByRatingRows) {
            countByRating.put(((Number) row[0]).intValue(), (Long) row[1]);
        }
        List<RatingCount> distribution = IntStream.iterate(5, rating -> rating >= 1, rating -> rating - 1)
                .mapToObj(rating -> new RatingCount(rating, countByRating.getOrDefault(rating, 0L)))
                .toList();
        long count = distribution.stream().mapToLong(RatingCount::count).sum();
        long ratingSum = distribution.stream().mapToLong(d -> d.rating() * d.count()).sum();
        return new RatingDistribution(distribution, count, count == 0 ? 0 : round2((double) ratingSum / count));
    }

    static double round2(double value) {
        return Math.round(value * 100) / 100.0;
    }
}
