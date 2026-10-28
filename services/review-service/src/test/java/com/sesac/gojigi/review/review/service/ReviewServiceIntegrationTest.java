package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
import com.sesac.gojigi.review.review.dto.request.ReviewUpdateRequest;
import com.sesac.gojigi.review.review.dto.response.ReviewResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.exception.ReviewErrorCode;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import com.sesac.gojigi.review.review.dto.response.ReviewImageResponse;
import com.sesac.gojigi.review.review.entity.ReviewImage;
import com.sesac.gojigi.review.review.repository.ReviewImageRepository;
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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 리뷰 작성 — 자격 · 이미 작성 · 기한 · 본문 정화 · 동시 제출 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, ReviewService.class, ReviewImages.class, ReviewServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)   // 서비스 메서드마다 실제로 커밋 (동시 제출 확인)
class ReviewServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final long BUYER = 4L;
    private static final String CONTENT = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.";
    /** 다른 테스트 · 실행과 상품주문 ID 가 겹치지 않게 */
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(System.nanoTime() % 1_000_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private ReviewService reviewService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private long saveEligibility(LocalDateTime confirmedAt) {
        long orderItemId = ORDER_ITEM_SEQ.incrementAndGet();
        eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(orderItemId).userId(BUYER).productId(101L).storeId(1L)
                .buyerDisplayName("곳간러버").productName("린넨 오버핏 셔츠").confirmedAt(confirmedAt)
                .build());
        return orderItemId;
    }

    private static ReviewCreateRequest request(long orderItemId, String content) {
        return new ReviewCreateRequest(orderItemId, 5, "<b>핏이 예뻐요</b>", content, ReviewFit.TRUE_TO_SIZE, null);
    }

    @Test
    @DisplayName("자격이 있으면 저장된다 — 상품 · 스토어 · 표시 이름은 자격에서, 본문 · 요약은 태그를 지우고")
    void write_eligible_savesSanitizedReview() {
        // given
        long orderItemId = saveEligibility(NOW.minusDays(1));

        // when
        Long reviewId = reviewService.write(BUYER, request(orderItemId, CONTENT + "<script>alert(1)</script>")).reviewId();

        // then
        Review review = reviewRepository.findById(reviewId).orElseThrow();
        assertThat(review.getOrderItemId()).isEqualTo(orderItemId);
        assertThat(review.getUserId()).isEqualTo(BUYER);
        assertThat(review.getProductId()).isEqualTo(101L);
        assertThat(review.getStoreId()).isEqualTo(1L);
        assertThat(review.getWriterDisplayName()).isEqualTo("곳간러버");
        assertThat(review.getRating()).isEqualTo(5);
        assertThat(review.getTitle()).isEqualTo("핏이 예뻐요");
        assertThat(review.getContent()).isEqualTo(CONTENT);
        assertThat(review.getFit()).isEqualTo(ReviewFit.TRUE_TO_SIZE);
    }

    @Test
    @DisplayName("자격이 없거나 남의 상품주문이면 같은 403 REVIEW_NOT_ELIGIBLE")
    void write_notEligible_throwsNotEligible() {
        // given
        long othersOrderItem = saveEligibility(NOW.minusDays(1));

        // when · then
        assertThatThrownBy(() -> reviewService.write(BUYER, request(-1L, CONTENT)))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_ELIGIBLE);
        assertThatThrownBy(() -> reviewService.write(99L, request(othersOrderItem, CONTENT)))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_ELIGIBLE);
    }

    @Test
    @DisplayName("이미 작성한 상품주문이면 409 REVIEW_ALREADY_WRITTEN — 삭제한 리뷰도 포함")
    void write_alreadyWritten_throwsAlreadyWritten() {
        // given
        long orderItemId = saveEligibility(NOW.minusDays(1));
        Long reviewId = reviewService.write(BUYER, request(orderItemId, CONTENT)).reviewId();
        Review review = reviewRepository.findById(reviewId).orElseThrow();
        review.softDelete(NOW);
        reviewRepository.save(review);

        // when · then
        assertThatThrownBy(() -> reviewService.write(BUYER, request(orderItemId, CONTENT)))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
    }

    @Test
    @DisplayName("구매확정 후 30일까지는 쓰고, 지나면 409 REVIEW_WRITE_EXPIRED")
    void write_afterThirtyDays_throwsExpired() {
        // given
        long lastDay = saveEligibility(NOW.minusDays(30));
        long expired = saveEligibility(NOW.minusDays(30).minusSeconds(1));

        // when · then
        assertThat(reviewService.write(BUYER, request(lastDay, CONTENT)).reviewId()).isNotNull();
        assertThatThrownBy(() -> reviewService.write(BUYER, request(expired, CONTENT)))
                .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_WRITE_EXPIRED);
    }

    @Test
    @DisplayName("태그를 지우면 본문이 비는 경우 400 INVALID_INPUT, 저장하지 않는다")
    void write_onlyTagsContent_throwsInvalidInput() {
        // given
        long orderItemId = saveEligibility(NOW.minusDays(1));

        // when · then
        assertThatThrownBy(() -> reviewService.write(BUYER, request(orderItemId, "<script>alert('xss-xss-xss')</script>")))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.INVALID_INPUT);
        assertThat(reviewRepository.existsByOrderItemId(orderItemId)).isFalse();
    }

    @Test
    @DisplayName("같은 상품주문을 동시에 5번 제출하면 1건만 저장되고 나머지는 REVIEW_ALREADY_WRITTEN")
    void write_concurrentSubmits_savesOnlyOne() throws Exception {
        // given
        long orderItemId = saveEligibility(NOW.minusDays(1));
        int threads = 5;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    return reviewService.write(BUYER, request(orderItemId, CONTENT));
                } catch (BusinessException e) {
                    return e.getErrorCode();
                }
            });
        }

        // when
        List<Object> results = new ArrayList<>();
        for (Future<Object> future : pool.invokeAll(tasks)) {
            results.add(future.get());
        }
        pool.shutdown();

        // then
        assertThat(results).filteredOn(r -> r == ReviewErrorCode.REVIEW_ALREADY_WRITTEN).hasSize(threads - 1);
        assertThat(reviewRepository.findAll()).filteredOn(r -> r.getOrderItemId() == orderItemId).hasSize(1);
    }

    @Nested
    @DisplayName("내 리뷰 수정")
    class Edit {

        private static final String NEW_CONTENT = "키 160인데 기장이 살짝 길어서 수선했어요. 그 외에는 만족합니다.";

        private Long writeReview(LocalDateTime confirmedAt) {
            return reviewService.write(BUYER, request(saveEligibility(confirmedAt), CONTENT)).reviewId();
        }

        private static ReviewUpdateRequest update(String content) {
            return new ReviewUpdateRequest(2, null, content, null, null);
        }

        @Test
        @DisplayName("통째로 바꾼다 — 비운 요약 · 사이즈 체감은 지워지고, 본문은 태그를 지우고, 판매자 답글은 남는다")
        void edit_own_replacesFieldsAndKeepsReply() {
            // given
            Long reviewId = writeReview(NOW.minusDays(1));
            Review review = reviewRepository.findById(reviewId).orElseThrow();
            review.reply("감사합니다", NOW);
            reviewRepository.save(review);

            // when
            ReviewResponse response = reviewService.edit(BUYER, reviewId, update("<p>" + NEW_CONTENT + "</p>"));

            // then
            assertThat(response).isEqualTo(new ReviewResponse(reviewId, 2, null, NEW_CONTENT, null, List.of()));
            Review found = reviewRepository.findById(reviewId).orElseThrow();
            assertThat(found.getRating()).isEqualTo(2);
            assertThat(found.getTitle()).isNull();
            assertThat(found.getContent()).isEqualTo(NEW_CONTENT);
            assertThat(found.getFit()).isNull();
            assertThat(found.getReplyContent()).isEqualTo("감사합니다");
        }

        @Test
        @DisplayName("작성 기한(30일)이 지나도 수정할 수 있다")
        void edit_afterWritePeriod_isAllowed() {
            // given — 40일 전 구매확정(지금은 작성 기한이 지남)한 상품주문에 기한 안에 써 둔 리뷰
            ReviewEligibility expired = eligibilityRepository.findByOrderItemId(saveEligibility(NOW.minusDays(40)))
                    .orElseThrow();
            Long reviewId = reviewRepository.save(Review.write(expired, 5, null, CONTENT, null)).getId();

            // when · then
            assertThat(reviewService.edit(BUYER, reviewId, update(NEW_CONTENT)).rating()).isEqualTo(2);
        }

        @Test
        @DisplayName("없는 · 삭제된 · 다른 회원의 리뷰는 같은 404 REVIEW_NOT_FOUND")
        void edit_notFoundDeletedOrOthers_throwsNotFound() {
            // given
            Long othersReviewId = writeReview(NOW.minusDays(1));
            Long deletedReviewId = writeReview(NOW.minusDays(1));
            Review deleted = reviewRepository.findById(deletedReviewId).orElseThrow();
            deleted.softDelete(NOW);
            reviewRepository.save(deleted);

            // when · then
            assertThatThrownBy(() -> reviewService.edit(BUYER, -1L, update(NEW_CONTENT)))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> reviewService.edit(99L, othersReviewId, update(NEW_CONTENT)))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> reviewService.edit(BUYER, deletedReviewId, update(NEW_CONTENT)))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
        }

        @Test
        @DisplayName("블라인드된 리뷰는 409 REVIEW_BLINDED, 내용이 바뀌지 않는다")
        void edit_blinded_throwsBlindedAndKeepsContent() {
            // given
            Long reviewId = writeReview(NOW.minusDays(1));
            Review review = reviewRepository.findById(reviewId).orElseThrow();
            review.blind("욕설 · 비방", NOW);
            reviewRepository.save(review);

            // when · then
            assertThatThrownBy(() -> reviewService.edit(BUYER, reviewId, update(NEW_CONTENT)))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_BLINDED);
            assertThat(reviewRepository.findById(reviewId).orElseThrow().getContent()).isEqualTo(CONTENT);
        }

        @Test
        @DisplayName("태그를 지우면 본문이 비는 경우 400 INVALID_INPUT, 내용이 바뀌지 않는다")
        void edit_onlyTagsContent_throwsInvalidInput() {
            // given
            Long reviewId = writeReview(NOW.minusDays(1));

            // when · then
            assertThatThrownBy(() -> reviewService.edit(BUYER, reviewId, update("<script>alert('xss-xss-xss')</script>")))
                    .hasFieldOrPropertyWithValue("errorCode", CommonErrorCode.INVALID_INPUT);
            assertThat(reviewRepository.findById(reviewId).orElseThrow().getContent()).isEqualTo(CONTENT);
        }
    }

    @Nested
    @DisplayName("내 리뷰 삭제")
    class Delete {

        private Long writeReview() {
            return reviewService.write(BUYER, request(saveEligibility(NOW.minusDays(1)), CONTENT)).reviewId();
        }

        @Test
        @DisplayName("지우면 삭제 시각이 남고(물리 삭제 없음), 같은 상품주문에 다시 쓸 수 없다")
        void delete_own_softDeletesAndBlocksRewrite() {
            // given
            Long reviewId = writeReview();
            long orderItemId = reviewRepository.findById(reviewId).orElseThrow().getOrderItemId();

            // when
            reviewService.delete(BUYER, reviewId);

            // then
            assertThat(reviewRepository.findById(reviewId).orElseThrow().getDeletedAt()).isEqualTo(NOW);
            assertThatThrownBy(() -> reviewService.write(BUYER, request(orderItemId, CONTENT)))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_ALREADY_WRITTEN);
        }

        @Test
        @DisplayName("블라인드된 리뷰도 지울 수 있다")
        void delete_blinded_isAllowed() {
            // given
            Long reviewId = writeReview();
            Review review = reviewRepository.findById(reviewId).orElseThrow();
            review.blind("욕설 · 비방", NOW);
            reviewRepository.save(review);

            // when
            reviewService.delete(BUYER, reviewId);

            // then
            assertThat(reviewRepository.findById(reviewId).orElseThrow().isDeleted()).isTrue();
        }

        @Test
        @DisplayName("없는 · 이미 삭제된 · 다른 회원의 리뷰는 같은 404 REVIEW_NOT_FOUND, 남의 리뷰는 그대로")
        void delete_notFoundDeletedOrOthers_throwsNotFound() {
            // given
            Long reviewId = writeReview();
            reviewService.delete(BUYER, reviewId);
            Long othersReviewId = writeReview();

            // when · then
            assertThatThrownBy(() -> reviewService.delete(BUYER, -1L))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> reviewService.delete(BUYER, reviewId))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThatThrownBy(() -> reviewService.delete(99L, othersReviewId))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_NOT_FOUND);
            assertThat(reviewRepository.findById(othersReviewId).orElseThrow().isDeleted()).isFalse();
        }
    }

    @Nested
    @DisplayName("사진")
    class Images {

        @Autowired
        private ReviewImageRepository reviewImageRepository;

        private List<String> keysOf(Long reviewId) {
            return reviewImageRepository.findByReviewIdsOrdered(List.of(reviewId)).stream()
                    .map(ReviewImage::getImageKey).toList();
        }

        @Test
        @DisplayName("작성 때 내 키 사진을 순서대로 저장한다")
        void write_withImages_savesInOrder() {
            // given
            long orderItemId = saveEligibility(NOW.minusDays(1));

            // when
            Long reviewId = reviewService.write(BUYER, new ReviewCreateRequest(orderItemId, 5, null, CONTENT, null,
                    List.of("reviews/4/b.png", "reviews/4/a.jpg"))).reviewId();

            // then
            assertThat(keysOf(reviewId)).containsExactly("reviews/4/b.png", "reviews/4/a.jpg");
        }

        @Test
        @DisplayName("남의 키로 작성하면 400 REVIEW_IMAGE_INVALID, 리뷰도 저장하지 않는다")
        void write_othersKey_rejectsWholeReview() {
            // given
            long orderItemId = saveEligibility(NOW.minusDays(1));

            // when · then
            assertThatThrownBy(() -> reviewService.write(BUYER, new ReviewCreateRequest(orderItemId, 5, null, CONTENT, null,
                    List.of("reviews/5/a.jpg"))))
                    .hasFieldOrPropertyWithValue("errorCode", ReviewErrorCode.REVIEW_IMAGE_INVALID);
            assertThat(reviewRepository.existsByOrderItemId(orderItemId)).isFalse();
        }

        @Test
        @DisplayName("수정은 사진을 통째로 바꾼다 — 남긴 기존 키 + 새 키 순서대로, 빈 목록이면 전부 삭제, 응답에 키 · URL")
        void edit_replacesImages() {
            // given
            long orderItemId = saveEligibility(NOW.minusDays(1));
            Long reviewId = reviewService.write(BUYER, new ReviewCreateRequest(orderItemId, 5, null, CONTENT, null,
                    List.of("reviews/4/a.jpg", "reviews/4/b.jpg"))).reviewId();

            // when
            ReviewResponse edited = reviewService.edit(BUYER, reviewId, new ReviewUpdateRequest(4, null, CONTENT, null,
                    List.of("reviews/4/c.webp", "reviews/4/a.jpg")));

            // then
            assertThat(keysOf(reviewId)).containsExactly("reviews/4/c.webp", "reviews/4/a.jpg");
            assertThat(edited.images()).extracting(ReviewImageResponse::key).containsExactly("reviews/4/c.webp", "reviews/4/a.jpg");
            assertThat(edited.images().getFirst().url()).endsWith("/reviews/4/c.webp");

            // when — 사진 모두 빼기
            reviewService.edit(BUYER, reviewId, new ReviewUpdateRequest(4, null, CONTENT, null, List.of()));

            // then
            assertThat(keysOf(reviewId)).isEmpty();
        }
    }
}
