package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewReplyRequest;
import com.sesac.gojigi.review.review.dto.request.SellerReviewTab;
import com.sesac.gojigi.review.review.dto.response.ReviewReplyResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse.ProductRating;
import com.sesac.gojigi.review.review.dto.response.RatingCount;
import com.sesac.gojigi.review.review.dto.response.SellerReviewSummaryResponse.TabCounts;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
import com.sesac.gojigi.review.review.entity.ReviewImage;
import com.sesac.gojigi.review.review.repository.ReviewImageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** 판매자 리뷰 관리 — 목록 탭 · 평점 요약 · 답글 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, SellerReviewService.class, ReviewImages.class, SellerReviewServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
class SellerReviewServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    /** 다른 테스트가 커밋한 리뷰와 섞이지 않게 이 테스트만의 스토어 ID */
    private static final long STORE = 801L;
    private static final long OTHER_STORE = 802L;
    private static final long SHIRT = 101L;
    private static final long PANTS = 102L;
    private static final String CONTENT = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.";
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(3_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private SellerReviewService sellerReviewService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private Review shirt5;
    private Review shirt1Blinded;
    private Review pants2;
    private Review pants4Replied;

    private Review saveReview(long storeId, long productId, String productName, int rating) {
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(4L).productId(productId).storeId(storeId)
                .buyerDisplayName("곳간러버").productName(productName).optionName("오트밀 / M")
                .confirmedAt(NOW.minusDays(1))
                .build());
        return reviewRepository.save(Review.write(eligibility, rating, null, CONTENT, null));
    }

    @BeforeEach
    void setUp() {
        // 오래된 것부터 저장 — 목록은 최신순
        shirt5 = saveReview(STORE, SHIRT, "린넨 셔츠", 5);
        shirt1Blinded = saveReview(STORE, SHIRT, "린넨 셔츠", 1);
        shirt1Blinded.blind("욕설 · 비방", NOW);
        pants2 = saveReview(STORE, PANTS, "와이드 팬츠", 2);
        pants4Replied = saveReview(STORE, PANTS, "와이드 팬츠", 4);
        pants4Replied.reply("감사합니다", NOW);
        saveReview(STORE, SHIRT, "린넨 셔츠", 3).softDelete(NOW);
        saveReview(OTHER_STORE, SHIRT, "린넨 셔츠", 5);
        reviewRepository.flush();
    }

    @Nested
    @DisplayName("목록")
    class Listing {

        @Test
        @DisplayName("전체 — 내 스토어 · 삭제 제외 · 블라인드 포함, 최신순, 작성자 표시 이름 · 상품 스냅샷")
        void list_all_returnsStoreReviewsNewestFirst() {
            PageResponse<SellerReviewResponse> page = sellerReviewService.list(STORE, SellerReviewTab.ALL, null, 0, 10);

            assertThat(page.content()).extracting(SellerReviewResponse::reviewId)
                    .containsExactly(pants4Replied.getId(), pants2.getId(), shirt1Blinded.getId(), shirt5.getId());
            SellerReviewResponse latest = page.content().getFirst();
            assertThat(latest.writerDisplayName()).isEqualTo("곳간러버");
            assertThat(latest.productName()).isEqualTo("와이드 팬츠");
            assertThat(latest.optionName()).isEqualTo("오트밀 / M");
            assertThat(latest.replyContent()).isEqualTo("감사합니다");
            assertThat(page.content().get(2).blinded()).isTrue();
        }

        @Test
        @DisplayName("답글 미작성 — 답글 없고 블라인드 아닌 것만 / 1~2점 — 블라인드 포함 / 상품 하나만")
        void list_tabsAndProductFilter() {
            assertThat(sellerReviewService.list(STORE, SellerReviewTab.NO_REPLY, null, 0, 10).content())
                    .extracting(SellerReviewResponse::reviewId).containsExactly(pants2.getId(), shirt5.getId());
            assertThat(sellerReviewService.list(STORE, SellerReviewTab.LOW_RATING, null, 0, 10).content())
                    .extracting(SellerReviewResponse::reviewId).containsExactly(pants2.getId(), shirt1Blinded.getId());
            assertThat(sellerReviewService.list(STORE, SellerReviewTab.ALL, SHIRT, 0, 10).content())
                    .extracting(SellerReviewResponse::reviewId).containsExactly(shirt1Blinded.getId(), shirt5.getId());
        }

        @Test
        @DisplayName("페이지 크기만큼 나눠 준다")
        void list_pages() {
            PageResponse<SellerReviewResponse> first = sellerReviewService.list(STORE, SellerReviewTab.ALL, null, 0, 3);

            assertThat(first.content()).hasSize(3);
            assertThat(first.totalElements()).isEqualTo(4);
            assertThat(first.hasNext()).isTrue();
        }
    }

    @Test
    @DisplayName("요약 — 평균 · 분포 · 상품별은 블라인드 · 삭제 제외, 탭 숫자는 목록 조건과 같다")
    void summarize_excludesBlindedFromRatings() {
        SellerReviewSummaryResponse summary = sellerReviewService.summarize(STORE);

        // 보이는 리뷰 5 · 2 · 4 → 평균 3.67
        assertThat(summary.ratedCount()).isEqualTo(3);
        assertThat(summary.averageRating()).isEqualTo(3.67);
        assertThat(summary.distribution()).extracting(RatingCount::rating).containsExactly(5, 4, 3, 2, 1);
        assertThat(summary.distribution()).extracting(RatingCount::count).containsExactly(1L, 1L, 0L, 1L, 0L);
        assertThat(summary.products()).containsExactly(
                new ProductRating(SHIRT, "린넨 셔츠", 5.0, 1),
                new ProductRating(PANTS, "와이드 팬츠", 3.0, 2));
        assertThat(summary.tabCounts()).isEqualTo(new TabCounts(4, 2, 2, 0));
    }

    @Test
    @DisplayName("리뷰가 없는 스토어 — 평균 0 · 분포 모두 0 · 상품 없음")
    void summarize_noReviews_returnsZeros() {
        SellerReviewSummaryResponse summary = sellerReviewService.summarize(899L);

        assertThat(summary.averageRating()).isZero();
        assertThat(summary.distribution()).extracting(RatingCount::count).containsOnly(0L);
        assertThat(summary.products()).isEmpty();
        assertThat(summary.tabCounts()).isEqualTo(new TabCounts(0, 0, 0, 0));
    }

    @Nested
    @DisplayName("답글")
    class Reply {

        @Test
        @DisplayName("없으면 작성 · 있으면 덮어쓰기, 태그는 지우고 시각을 남긴다")
        void reply_writesOrOverwrites() {
            ReviewReplyResponse written = sellerReviewService.reply(STORE, pants2.getId(),
                    new ReviewReplyRequest("<b>불편을 드려 죄송합니다.</b>"));
            ReviewReplyResponse edited = sellerReviewService.reply(STORE, pants4Replied.getId(),
                    new ReviewReplyRequest("감사합니다! 또 찾아 주세요"));

            assertThat(written.replyContent()).isEqualTo("불편을 드려 죄송합니다.");
            assertThat(written.repliedAt()).isEqualTo(NOW.atZone(KST).toOffsetDateTime());
            assertThat(edited.replyContent()).isEqualTo("감사합니다! 또 찾아 주세요");
            assertThat(reviewRepository.findById(pants2.getId()).orElseThrow().getReplyContent())
                    .isEqualTo("불편을 드려 죄송합니다.");
        }

        @Test
        @DisplayName("블라인드된 리뷰는 409 REVIEW_BLINDED")
        void reply_blinded_throwsBlinded() {
            assertThatThrownBy(() -> sellerReviewService.reply(STORE, shirt1Blinded.getId(), new ReviewReplyRequest("답글")))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_BLINDED);
        }

        @Test
        @DisplayName("없는 · 다른 스토어 리뷰는 404 REVIEW_NOT_FOUND, 태그만 있으면 400 INVALID_INPUT")
        void reply_notFoundOrEmpty() {
            assertThatThrownBy(() -> sellerReviewService.reply(STORE, -1L, new ReviewReplyRequest("답글")))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> sellerReviewService.reply(OTHER_STORE, shirt5.getId(), new ReviewReplyRequest("답글")))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> sellerReviewService.reply(STORE, shirt5.getId(), new ReviewReplyRequest("<script>x</script>")))
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.INVALID_INPUT);
        }
    }

    @Test
    @DisplayName("스토어 정보가 없으면 목록 · 요약 · 답글 모두 403 FORBIDDEN")
    void withoutStore_throwsForbidden() {
        assertThatThrownBy(() -> sellerReviewService.list(null, SellerReviewTab.ALL, null, 0, 10))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> sellerReviewService.summarize(null))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
        assertThatThrownBy(() -> sellerReviewService.reply(null, shirt5.getId(), new ReviewReplyRequest("답글")))
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.FORBIDDEN);
    }

    @Test
    @DisplayName("포토 탭 — 사진 있는 리뷰만(블라인드 포함), 탭 숫자 photo 도 같다, 응답에 사진 URL")
    void photoTab_listsReviewsWithImages(@Autowired ReviewImageRepository reviewImageRepository) {
        // given
        reviewImageRepository.save(new ReviewImage(shirt1Blinded, "reviews/4/p.jpg", 0));
        reviewImageRepository.save(new ReviewImage(pants2, "reviews/4/q.jpg", 0));
        reviewImageRepository.flush();

        // when
        var photos = sellerReviewService.list(STORE, SellerReviewTab.PHOTO, null, 0, 10).content();

        // then
        assertThat(photos).extracting(SellerReviewResponse::reviewId).containsExactly(pants2.getId(), shirt1Blinded.getId());
        assertThat(photos.getFirst().imageUrls()).singleElement().asString().endsWith("/reviews/4/q.jpg");
        assertThat(sellerReviewService.summarize(STORE).tabCounts().photo()).isEqualTo(2);
    }
}
