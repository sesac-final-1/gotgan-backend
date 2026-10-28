package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewBlindRequest;
import com.sesac.gojigi.review.review.dto.response.InternalReviewResponse;
import com.sesac.gojigi.review.review.dto.response.InternalUserReviewResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;
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

/** admin 신고 처리 내부 API — 조회 · 블라인드 · 해제 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, InternalReviewService.class, InternalReviewServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
class InternalReviewServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final String CONTENT = "판매자 대응 진짜 최악이네요 이딴 걸 돈 받고 파냐";
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(4_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private InternalReviewService internalReviewService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private EntityManager em;

    private Review saveReview() {
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(4L).productId(101L).storeId(1L)
                .buyerDisplayName("곳간러버").productName("린넨 셔츠").confirmedAt(NOW.minusDays(1))
                .build());
        Review review = reviewRepository.saveAndFlush(Review.write(eligibility, 1, null, CONTENT, null));
        em.clear();   // created_at 을 DB 에서 다시 읽게
        return review;
    }

    @Test
    @DisplayName("조회 — 계약 필드(상품 · 스토어 · 작성자 · 별점 · 본문 · 사진 · 블라인드 · 작성 시각)")
    void get_returnsContractFields() {
        // given
        Review review = saveReview();

        // when
        InternalReviewResponse response = internalReviewService.get(review.getId());

        // then
        assertThat(response.reviewId()).isEqualTo(review.getId());
        assertThat(response.productId()).isEqualTo(101L);
        assertThat(response.storeId()).isEqualTo(1L);
        assertThat(response.userId()).isEqualTo(4L);
        assertThat(response.rating()).isEqualTo(1);
        assertThat(response.content()).isEqualTo(CONTENT);
        assertThat(response.imageUrls()).isEmpty();
        assertThat(response.blinded()).isFalse();
        assertThat(response.blindReason()).isNull();
        assertThat(response.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("없는 · 삭제된 리뷰는 조회 · 블라인드 모두 404 REVIEW_NOT_FOUND")
    void notFoundOrDeleted_throwsNotFound() {
        // given
        Review deleted = saveReview();
        Review found = reviewRepository.findById(deleted.getId()).orElseThrow();
        found.softDelete(NOW);
        reviewRepository.flush();

        // when · then
        assertThatThrownBy(() -> internalReviewService.get(-1L))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
        assertThatThrownBy(() -> internalReviewService.get(deleted.getId()))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
        assertThatThrownBy(() -> internalReviewService.changeBlind(deleted.getId(), new ReviewBlindRequest(true, "욕설")))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
    }

    @Nested
    @DisplayName("블라인드 · 해제")
    class ChangeBlind {

        @Test
        @DisplayName("블라인드하면 사유(앞뒤 공백 제거) · 시각이 저장되고, 해제하면 비운다")
        void blindThenUnblind() {
            // given
            Review review = saveReview();

            // when
            InternalReviewResponse blinded = internalReviewService.changeBlind(review.getId(),
                    new ReviewBlindRequest(true, "  욕설 · 비방 "));

            // then
            assertThat(blinded.blinded()).isTrue();
            assertThat(blinded.blindReason()).isEqualTo("욕설 · 비방");
            assertThat(reviewRepository.findById(review.getId()).orElseThrow().getBlindedAt()).isEqualTo(NOW);

            // when — 해제 사유는 저장하지 않는다
            InternalReviewResponse unblinded = internalReviewService.changeBlind(review.getId(),
                    new ReviewBlindRequest(false, "오신고"));

            // then
            assertThat(unblinded.blinded()).isFalse();
            assertThat(unblinded.blindReason()).isNull();
            assertThat(reviewRepository.findById(review.getId()).orElseThrow().getBlindedAt()).isNull();
        }

        @Test
        @DisplayName("같은 상태 재요청은 그대로 — 블라인드 두 번은 처음 사유 유지, 해제 두 번 · 사유 없는 해제도 성공")
        void sameState_isIdempotent() {
            // given
            Review review = saveReview();
            internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(true, "욕설 · 비방"));

            // when · then
            assertThat(internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(true, "광고")).blindReason())
                    .isEqualTo("욕설 · 비방");
            assertThat(internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(false, null)).blinded())
                    .isFalse();
            assertThat(internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(false, null)).blinded())
                    .isFalse();
        }

        @Test
        @DisplayName("블라인드인데 사유가 없거나 공백이면 400 INVALID_INPUT, 상태는 그대로")
        void blindWithoutReason_throwsInvalidInput() {
            // given
            Review review = saveReview();

            // when · then
            assertThatThrownBy(() -> internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(true, null)))
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.INVALID_INPUT);
            assertThatThrownBy(() -> internalReviewService.changeBlind(review.getId(), new ReviewBlindRequest(true, "  ")))
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.INVALID_INPUT);
            assertThat(reviewRepository.findById(review.getId()).orElseThrow().isBlinded()).isFalse();
        }
    }

    @Test
    @DisplayName("회원별 리뷰 — 그 회원 것만 · 삭제 제외 · 블라인드 포함 · 최신순, 상품명은 자격 스냅샷, 없는 회원은 빈 목록")
    void listByUser_ownNotDeletedNewestFirst() {
        // given
        long user = 7_301L;
        Review first = saveReviewOf(user);
        Review blinded = saveReviewOf(user);
        Review deleted = saveReviewOf(user);
        saveReviewOf(user + 1);
        Review b = reviewRepository.findById(blinded.getId()).orElseThrow();
        b.blind("욕설 · 비방", NOW);
        reviewRepository.findById(deleted.getId()).orElseThrow().softDelete(NOW);
        reviewRepository.flush();
        em.clear();

        // when
        PageResponse<InternalUserReviewResponse> page = internalReviewService.listByUser(user, 0, 10);

        // then
        assertThat(page.content()).extracting(InternalUserReviewResponse::reviewId)
                .containsExactly(blinded.getId(), first.getId());
        InternalUserReviewResponse latest = page.content().getFirst();
        assertThat(latest.blinded()).isTrue();
        assertThat(latest.blindReason()).isEqualTo("욕설 · 비방");
        assertThat(latest.productName()).isEqualTo("린넨 셔츠");
        assertThat(latest.productId()).isEqualTo(101L);
        assertThat(latest.storeId()).isEqualTo(1L);
        assertThat(latest.createdAt()).isNotNull();
        assertThat(internalReviewService.listByUser(9_999_999L, 0, 10).content()).isEmpty();
        assertThat(internalReviewService.listByUser(user, 0, 1).hasNext()).isTrue();
    }

    private Review saveReviewOf(long userId) {
        ReviewEligibility eligibility = eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(userId).productId(101L).storeId(1L)
                .buyerDisplayName("곳간러버").productName("린넨 셔츠").confirmedAt(NOW.minusDays(1))
                .build());
        return reviewRepository.saveAndFlush(Review.write(eligibility, 4, null, CONTENT, null));
    }
}
