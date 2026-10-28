package com.sesac.gojigi.review.review.service;

import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여러 상품 · 스토어의 평균 별점 · 리뷰 수 (review.md 10절) — 상품 카드 · 목록 · 스토어 홈 · 관리자 스토어 화면. 담당 이경석.
 * product · store 는 다른 서비스를 부르지 않으므로 화면이 받은 ID 묶음으로 한 번에 묻는다. 요청한 순서대로(중복 제거),
 * 리뷰가 없으면 0 · 0 으로 채워 화면이 빈 값을 따로 처리하지 않게 한다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RatingQueryService {

    private final ReviewRepository reviewRepository;

    public List<ProductRatingResponse> rateProducts(List<Long> productIds) {
        Set<Long> ids = new LinkedHashSet<>(productIds);
        return fillInOrder(ids, reviewRepository.summarizeByProductIds(ids),
                (id, rating) -> new ProductRatingResponse(id, rating.average, rating.count));
    }

    public List<StoreRatingResponse> rateStores(List<Long> storeIds) {
        Set<Long> ids = new LinkedHashSet<>(storeIds);
        return fillInOrder(ids, reviewRepository.summarizeByStoreIds(ids),
                (id, rating) -> new StoreRatingResponse(id, rating.average, rating.count));
    }

    /** [id, avg, count] 집계 행을 요청 순서대로 펼친다 — 행이 없는 ID 는 0 · 0 */
    private static <T> List<T> fillInOrder(Set<Long> ids, List<Object[]> rows, BiFunction<Long, Rating, T> toResponse) {
        Map<Long, Rating> byId = new HashMap<>();
        rows.forEach(row -> byId.put((Long) row[0], new Rating(RatingDistribution.round2((Double) row[1]), (Long) row[2])));
        return ids.stream()
                .map(id -> toResponse.apply(id, byId.getOrDefault(id, Rating.NONE)))
                .toList();
    }

    private record Rating(double average, long count) {
        static final Rating NONE = new Rating(0, 0);
    }
}
