package com.sesac.gojigi.review.review.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.common.web.exception.BusinessException;
import com.sesac.gojigi.common.web.exception.CommonErrorCode;
import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.review.dto.request.ReviewCreateRequest;
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
import org.junit.jupiter.api.DisplayName;
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
@Import({ TestcontainersConfiguration.class, ReviewService.class, ReviewServiceIntegrationTest.FixedClock.class })
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
        return new ReviewCreateRequest(orderItemId, 5, "<b>핏이 예뻐요</b>", content, ReviewFit.TRUE_TO_SIZE);
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
}
