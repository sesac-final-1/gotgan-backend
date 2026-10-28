package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.response.MyReviewResponse;
import com.sesac.gojigi.review.review.dto.response.WritableReviewResponse;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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

/** 마이페이지 리뷰 관리 조회 — 내가 쓴 리뷰 · 작성 가능한 리뷰 (MySQL 8.4 + Flyway) */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ TestcontainersConfiguration.class, MyReviewQueryService.class, ReviewImages.class, MyReviewQueryServiceIntegrationTest.FixedClock.class })
@ActiveProfiles("test")
class MyReviewQueryServiceIntegrationTest {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);
    private static final long BUYER = 7001L;
    private static final long OTHER = 7002L;
    private static final String CONTENT = "평소 M 입는데 M 주문했고 어깨가 살짝 내려오는 오버핏이에요.";
    /** 다른 테스트가 커밋한 상품주문 ID 와 겹치지 않게 */
    private static final AtomicLong ORDER_ITEM_SEQ = new AtomicLong(2_000_000_000L + System.nanoTime() % 1_000_000_000L);

    @TestConfiguration(proxyBeanMethods = false)
    static class FixedClock {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW.atZone(KST).toInstant(), KST);
        }
    }

    @Autowired
    private MyReviewQueryService myReviewQueryService;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;

    private ReviewEligibility saveEligibility(long userId, LocalDateTime confirmedAt) {
        return eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(ORDER_ITEM_SEQ.incrementAndGet()).userId(userId).productId(101L).storeId(1L)
                .buyerDisplayName("곳간러버").productName("린넨 오버핏 셔츠").optionName("오트밀 / M")
                .thumbnailUrl("https://cdn.gotgan.click/products/1/a1b2.jpg").confirmedAt(confirmedAt)
                .build());
    }

    private Review saveReview(long userId, int rating) {
        return reviewRepository.saveAndFlush(
                Review.write(saveEligibility(userId, NOW.minusDays(1)), rating, null, CONTENT, ReviewFit.TRUE_TO_SIZE));
    }

    @Nested
    @DisplayName("내가 쓴 리뷰")
    class ListMine {

        @Test
        @DisplayName("내 리뷰만 최신 작성순 — 삭제한 리뷰는 빠지고 블라인드 · 답글은 보인다, 상품 표시값은 자격 스냅샷")
        void listMine_returnsOwnNotDeletedNewestFirst() {
            // given
            Review first = saveReview(BUYER, 5);
            Review blinded = saveReview(BUYER, 1);
            blinded.blind("욕설 · 비방", NOW);
            Review deleted = saveReview(BUYER, 3);
            deleted.softDelete(NOW);
            Review replied = saveReview(BUYER, 4);
            replied.reply("감사합니다", NOW);
            reviewRepository.flush();
            saveReview(OTHER, 2);

            // when
            PageResponse<MyReviewResponse> page = myReviewQueryService.listMine(BUYER, 0, 10);

            // then
            assertThat(page.content()).extracting(MyReviewResponse::reviewId)
                    .containsExactly(replied.getId(), blinded.getId(), first.getId());
            assertThat(page.totalElements()).isEqualTo(3);
            MyReviewResponse latest = page.content().getFirst();
            assertThat(latest.productName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(latest.optionName()).isEqualTo("오트밀 / M");
            assertThat(latest.thumbnailUrl()).isEqualTo("https://cdn.gotgan.click/products/1/a1b2.jpg");
            assertThat(latest.replyContent()).isEqualTo("감사합니다");
            assertThat(latest.repliedAt()).isEqualTo(OffsetDateTime.of(NOW, KST.getRules().getOffset(NOW)));
            assertThat(latest.createdAt()).isNotNull();
            assertThat(page.content().get(1).blinded()).isTrue();
        }

        @Test
        @DisplayName("페이지 크기만큼 나눠 주고 다음 페이지 여부를 알려 준다")
        void listMine_pagesResults() {
            // given
            long user = BUYER + 10;
            for (int i = 0; i < 3; i++) {
                saveReview(user, 5);
            }

            // when
            PageResponse<MyReviewResponse> first = myReviewQueryService.listMine(user, 0, 2);
            PageResponse<MyReviewResponse> second = myReviewQueryService.listMine(user, 1, 2);

            // then
            assertThat(first.content()).hasSize(2);
            assertThat(first.hasNext()).isTrue();
            assertThat(first.totalPages()).isEqualTo(2);
            assertThat(second.content()).hasSize(1);
            assertThat(second.hasNext()).isFalse();
        }
    }

    @Nested
    @DisplayName("작성 가능한 리뷰")
    class ListWritable {

        @Test
        @DisplayName("기한 안 · 리뷰 없는 내 자격만 기한 임박순 — 지난 기한 · 쓴 리뷰 · 삭제한 리뷰 · 남의 자격은 빠진다")
        void listWritable_returnsOpenEligibilitiesBySoonestDeadline() {
            // given
            long user = BUYER + 20;
            ReviewEligibility recent = saveEligibility(user, NOW.minusDays(1));
            ReviewEligibility lastMoment = saveEligibility(user, NOW.minusDays(30));   // 기한 = 지금 → 아직 가능
            saveEligibility(user, NOW.minusDays(30).minusSeconds(1));                 // 기한 지남
            reviewRepository.save(Review.write(saveEligibility(user, NOW.minusDays(2)), 5, null, CONTENT, null));
            Review deleted = reviewRepository.save(Review.write(saveEligibility(user, NOW.minusDays(3)), 5, null, CONTENT, null));
            deleted.softDelete(NOW);
            saveEligibility(OTHER, NOW.minusDays(1));
            reviewRepository.flush();

            // when
            var writable = myReviewQueryService.listWritable(user);

            // then
            assertThat(writable).extracting(WritableReviewResponse::orderItemId)
                    .containsExactly(lastMoment.getOrderItemId(), recent.getOrderItemId());
            WritableReviewResponse soonest = writable.getFirst();
            assertThat(soonest.productName()).isEqualTo("린넨 오버핏 셔츠");
            assertThat(soonest.optionName()).isEqualTo("오트밀 / M");
            assertThat(soonest.thumbnailUrl()).isEqualTo("https://cdn.gotgan.click/products/1/a1b2.jpg");
            assertThat(soonest.expiresAt()).isEqualTo(NOW.atZone(KST).toOffsetDateTime());
            assertThat(soonest.confirmedAt()).isEqualTo(NOW.minusDays(30).atZone(KST).toOffsetDateTime());
        }

        @Test
        @DisplayName("자격이 없으면 빈 목록")
        void listWritable_none_returnsEmpty() {
            assertThat(myReviewQueryService.listWritable(BUYER + 30)).isEmpty();
        }
    }

    @Test
    @DisplayName("내가 쓴 리뷰에 사진 키 · URL 이 순서대로 붙는다 (사진 없는 리뷰는 빈 목록)")
    void listMine_includesImages(@Autowired ReviewImageRepository reviewImageRepository) {
        // given
        long user = BUYER + 40;
        Review withPhotos = saveReview(user, 5);
        reviewImageRepository.save(new ReviewImage(withPhotos, "reviews/7041/b.jpg", 1));
        reviewImageRepository.save(new ReviewImage(withPhotos, "reviews/7041/a.jpg", 0));
        Review noPhoto = saveReview(user, 4);
        reviewImageRepository.flush();

        // when
        var content = myReviewQueryService.listMine(user, 0, 10).content();

        // then
        MyReviewResponse photo = content.stream().filter(r -> r.reviewId().equals(withPhotos.getId())).findFirst().orElseThrow();
        assertThat(photo.images()).extracting(ReviewImageResponse::key).containsExactly("reviews/7041/a.jpg", "reviews/7041/b.jpg");
        assertThat(photo.images().getFirst().url()).endsWith("/reviews/7041/a.jpg");
        assertThat(content.stream().filter(r -> r.reviewId().equals(noPhoto.getId())).findFirst().orElseThrow().images()).isEmpty();
    }
}
