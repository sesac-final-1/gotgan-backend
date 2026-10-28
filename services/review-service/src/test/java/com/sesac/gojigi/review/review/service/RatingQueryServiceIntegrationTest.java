package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.response.ProductRatingResponse;
import com.sesac.gojigi.review.review.dto.response.StoreRatingResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 여러 상품 · 스토어 평점 — 순서 · 중복 · 0 채움 · 블라인드/삭제 제외 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, RatingQueryService.class })
@ActiveProfiles("test")
class RatingQueryServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    /** 다른 테스트가 커밋한 리뷰와 섞이지 않게 이 테스트만의 상품 · 스토어 ID */
    private static final long SHIRT = 9201L;
    private static final long PANTS = 9202L;
    private static final long NO_REVIEW = 9299L;
    private static final long STORE_A = 921L;
    private static final long STORE_B = 922L;
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(6_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    private RatingQueryService ratingQueryService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private Review saveReview(long storeId, long productId, int rating) {
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(4L).productId(productId).storeId(storeId)
                .buyerDisplayName("곳간러버").productName("상품").confirmedAt(NOW.minusDays(1))
                .build());
        return reviewRepository.save(Review.write(eligibility, rating, null, "평소 M 입는데 M 주문했고 오버핏이에요.", null));
    }

    @BeforeEach
    void setUp() {
        saveReview(STORE_A, SHIRT, 5);
        saveReview(STORE_A, SHIRT, 4);
        saveReview(STORE_A, SHIRT, 4);
        saveReview(STORE_A, SHIRT, 1).blind("욕설 · 비방", NOW);
        saveReview(STORE_A, PANTS, 2).softDelete(NOW);
        saveReview(STORE_B, PANTS, 3);
        reviewRepository.flush();
    }

    @Test
    @DisplayName("상품 — 요청 순서대로 · 중복은 한 번 · 리뷰 없으면 0, 블라인드 · 삭제 제외 평균은 둘째 자리")
    void rateProducts_inRequestOrderWithZeros() {
        List<ProductRatingResponse> ratings = ratingQueryService.rateProducts(List.of(NO_REVIEW, SHIRT, PANTS, SHIRT));

        assertThat(ratings).containsExactly(
                new ProductRatingResponse(NO_REVIEW, 0, 0),
                new ProductRatingResponse(SHIRT, 4.33, 3),
                new ProductRatingResponse(PANTS, 3.0, 1));
    }

    @Test
    @DisplayName("스토어 — 요청 순서대로, 리뷰 없는 스토어는 0")
    void rateStores_inRequestOrderWithZeros() {
        List<StoreRatingResponse> ratings = ratingQueryService.rateStores(List.of(STORE_B, STORE_A, 999L));

        assertThat(ratings).containsExactly(
                new StoreRatingResponse(STORE_B, 3.0, 1),
                new StoreRatingResponse(STORE_A, 4.33, 3),
                new StoreRatingResponse(999L, 0, 0));
    }
}
