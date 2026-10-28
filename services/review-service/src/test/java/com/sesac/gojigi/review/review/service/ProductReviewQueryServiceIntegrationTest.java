package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ProductReviewSort;
import com.sesac.gojigi.review.review.dto.response.ProductReviewResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.ProductReviewSummaryResponse.FitCount;
import com.sesac.gojigi.review.review.dto.response.RatingCount;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 상품 상세 공개 리뷰 — 목록 필터 · 정렬 · 요약 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, ProductReviewQueryService.class })
@ActiveProfiles("test")
class ProductReviewQueryServiceIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    /** 다른 테스트가 커밋한 리뷰와 섞이지 않게 이 테스트만의 상품 ID */
    private static final long PRODUCT = 9101L;
    private static final long OTHER_PRODUCT = 9102L;
    private static final String CONTENT = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.";
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(5_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @Autowired
    private ProductReviewQueryService productReviewQueryService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private Review r5Fit;
    private Review r3Small;
    private Review r5Replied;

    private Review saveReview(long productId, int rating, ReviewFit fit) {
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(4L).productId(productId).storeId(1L)
                .buyerDisplayName("곳간러버").productName("린넨 셔츠").optionName("오트밀 / M").confirmedAt(NOW.minusDays(1))
                .build());
        return reviewRepository.save(Review.write(eligibility, rating, null, CONTENT, fit));
    }

    @BeforeEach
    void setUp() {
        // 오래된 것부터 — LATEST 는 역순
        r5Fit = saveReview(PRODUCT, 5, ReviewFit.TRUE_TO_SIZE);
        r3Small = saveReview(PRODUCT, 3, ReviewFit.SMALL);
        r5Replied = saveReview(PRODUCT, 5, null);
        r5Replied.reply("감사합니다", NOW);
        saveReview(PRODUCT, 1, ReviewFit.LARGE).blind("욕설 · 비방", NOW);
        saveReview(PRODUCT, 2, ReviewFit.LARGE).softDelete(NOW);
        saveReview(OTHER_PRODUCT, 4, ReviewFit.SMALL);
        reviewRepository.flush();
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("최신순 — 이 상품만 · 블라인드 · 삭제 제외, 작성자 표시 이름 · 옵션명 · 답글")
        void list_latest_excludesBlindedAndDeleted() {
            PageResponse<ProductReviewResponse> page = productReviewQueryService.list(PRODUCT, null, ProductReviewSort.LATEST, 0, 10);

            assertThat(page.content()).extracting(ProductReviewResponse::reviewId)
                    .containsExactly(r5Replied.getId(), r3Small.getId(), r5Fit.getId());
            ProductReviewResponse latest = page.content().getFirst();
            assertThat(latest.writerDisplayName()).isEqualTo("곳간러버");
            assertThat(latest.optionName()).isEqualTo("오트밀 / M");
            assertThat(latest.replyContent()).isEqualTo("감사합니다");
            assertThat(latest.createdAt()).isNotNull();
        }

        @Test
        @DisplayName("평점 높은순 · 낮은순 — 같은 별점이면 최신 먼저")
        void list_ratingSorts() {
            assertThat(productReviewQueryService.list(PRODUCT, null, ProductReviewSort.RATING_HIGH, 0, 10).content())
                    .extracting(ProductReviewResponse::reviewId)
                    .containsExactly(r5Replied.getId(), r5Fit.getId(), r3Small.getId());
            assertThat(productReviewQueryService.list(PRODUCT, null, ProductReviewSort.RATING_LOW, 0, 10).content())
                    .extracting(ProductReviewResponse::reviewId)
                    .containsExactly(r3Small.getId(), r5Replied.getId(), r5Fit.getId());
        }

        @Test
        @DisplayName("별점 하나만 · 페이지 나눔 · 없는 상품은 빈 목록")
        void list_ratingFilterPagingAndUnknownProduct() {
            assertThat(productReviewQueryService.list(PRODUCT, 5, ProductReviewSort.LATEST, 0, 10).content())
                    .extracting(ProductReviewResponse::reviewId).containsExactly(r5Replied.getId(), r5Fit.getId());
            assertThat(productReviewQueryService.list(PRODUCT, 1, ProductReviewSort.LATEST, 0, 10).content()).isEmpty();

            PageResponse<ProductReviewResponse> first = productReviewQueryService.list(PRODUCT, null, ProductReviewSort.LATEST, 0, 2);
            assertThat(first.content()).hasSize(2);
            assertThat(first.totalElements()).isEqualTo(3);
            assertThat(first.hasNext()).isTrue();

            assertThat(productReviewQueryService.list(9999L, null, ProductReviewSort.LATEST, 0, 10).content()).isEmpty();
        }
    }

    @Test
    @DisplayName("요약 — 평균 · 리뷰 수 · 별점 분포 · 사이즈 체감은 블라인드 · 삭제 제외")
    void summarize_excludesBlindedAndDeleted() {
        ProductReviewSummaryResponse summary = productReviewQueryService.summarize(PRODUCT);

        // 보이는 리뷰 5 · 3 · 5 → 4.33
        assertThat(summary.reviewCount()).isEqualTo(3);
        assertThat(summary.averageRating()).isEqualTo(4.33);
        assertThat(summary.distribution()).extracting(RatingCount::count).containsExactly(2L, 0L, 1L, 0L, 0L);
        assertThat(summary.fits()).containsExactly(
                new FitCount(ReviewFit.SMALL, 1), new FitCount(ReviewFit.TRUE_TO_SIZE, 1), new FitCount(ReviewFit.LARGE, 0));
    }

    @Test
    @DisplayName("리뷰 없는 상품 — 평균 0 · 분포 · 체감 모두 0")
    void summarize_noReviews_returnsZeros() {
        ProductReviewSummaryResponse summary = productReviewQueryService.summarize(9999L);

        assertThat(summary.reviewCount()).isZero();
        assertThat(summary.averageRating()).isZero();
        assertThat(summary.distribution()).hasSize(5).extracting(RatingCount::count).containsOnly(0L);
        assertThat(summary.fits()).hasSize(3).extracting(FitCount::count).containsOnly(0L);
    }
}
