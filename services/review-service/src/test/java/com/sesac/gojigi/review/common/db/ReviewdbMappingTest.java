package com.sesac.gojigi.review.common.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sesac.gojigi.review.TestcontainersConfiguration;
import com.sesac.gojigi.review.common.event.ProcessedEvent;
import com.sesac.gojigi.review.common.event.ProcessedEventRepository;
import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import com.sesac.gojigi.review.eligibility.repository.ReviewEligibilityRepository;
import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import com.sesac.gojigi.review.inquiry.repository.ProductInquiryRepository;
import com.sesac.gojigi.review.review.entity.Review;
import com.sesac.gojigi.review.review.entity.ReviewFit;
import com.sesac.gojigi.review.review.entity.ReviewImage;
import com.sesac.gojigi.review.review.repository.ReviewImageRepository;
import com.sesac.gojigi.review.review.repository.ReviewRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * reviewdb 마이그레이션(V2 ~ V5) ↔ 엔티티 매핑 검증 — 실제 MySQL 8.4(Testcontainers) + Flyway, ddl-auto: validate 로 컨텍스트가 뜨면 컬럼 · 타입 일치.
 * 마이그레이션을 추가하면 버전 기대값을 함께 고칠 것.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class ReviewdbMappingTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);

    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ReviewImageRepository reviewImageRepository;
    @Autowired
    private ProductInquiryRepository inquiryRepository;
    @Autowired
    private ProcessedEventRepository processedEventRepository;
    @Autowired
    private EntityManager em;
    @Autowired
    private JdbcTemplate jdbc;

    private ReviewEligibility saveEligibility(long orderItemId) {
        return eligibilityRepository.save(ReviewEligibility.builder()
                .orderItemId(orderItemId).userId(4L).productId(101L).storeId(1L)
                .buyerDisplayName("홍*동").productName("린넨 오버핏 셔츠").confirmedAt(NOW.minusDays(1))
                .build());
    }

    @Test
    @DisplayName("V6 까지 적용되고 helpful_count · review_image.file_size 는 없다, 문의 스냅샷 칼럼은 있다")
    void migrate_upToV6_dropsUnusedColumns() {
        assertThat(jdbc.queryForObject(
                "SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isEqualTo(6);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review_image' AND COLUMN_NAME = 'file_size'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'review' AND COLUMN_NAME = 'helpful_count'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'product_inquiry'"
                        + " AND COLUMN_NAME IN ('product_name', 'thumbnail_url', 'writer_display_name')",
                Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("리뷰 · 사진을 저장하고 다시 읽으면 값과 DB 기본값(시각 · 블라인드)이 들어 있다")
    void saveReviewWithImage_roundTrip() {
        // given
        Review review = Review.write(saveEligibility(11L), 5, "핏이 예뻐요", "<p>오버핏</p>", ReviewFit.TRUE_TO_SIZE);
        reviewRepository.save(review);
        reviewImageRepository.save(new ReviewImage(review, "reviews/11/a.jpg", 0));
        review.blind("욕설 · 비방", NOW);
        review.reply("감사합니다", NOW);
        em.flush();
        em.clear();

        // when
        Review found = reviewRepository.findById(review.getId()).orElseThrow();
        ReviewImage image = reviewImageRepository.findAll().getFirst();

        // then
        assertThat(found.getRating()).isEqualTo(5);
        assertThat(found.getFit()).isEqualTo(ReviewFit.TRUE_TO_SIZE);
        assertThat(found.getWriterDisplayName()).isEqualTo("홍*동");
        assertThat(found.getEligibility().getOrderItemId()).isEqualTo(11L);
        assertThat(found.isBlinded()).isTrue();
        assertThat(found.getBlindReason()).isEqualTo("욕설 · 비방");
        assertThat(found.getReplyContent()).isEqualTo("감사합니다");
        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
        assertThat(image.getReview().getId()).isEqualTo(review.getId());
        assertThat(image.getImageKey()).isEqualTo("reviews/11/a.jpg");
        assertThat(image.getSortOrder()).isZero();
    }

    @Test
    @DisplayName("같은 상품주문에 리뷰 두 번은 uk_review_order_item 이 막는다")
    void saveReview_duplicateOrderItem_isRejected() {
        // given
        ReviewEligibility eligibility = saveEligibility(12L);
        reviewRepository.saveAndFlush(Review.write(eligibility, 5, null, "좋아요", null));

        // when · then
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(Review.write(eligibility, 4, null, "또 써요", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("별점 1~5 밖은 ck_review_rating 이 막는다")
    void saveReview_ratingOutOfRange_isRejected() {
        // given
        ReviewEligibility eligibility = saveEligibility(13L);

        // when · then
        assertThatThrownBy(() -> reviewRepository.saveAndFlush(Review.write(eligibility, 6, null, "최고", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("상품문의를 저장하고 다시 읽으면 비밀글 · 답변이 들어 있다")
    void saveInquiry_roundTrip() {
        // given
        ProductInquiry inquiry = inquiryRepository.save(ProductInquiry.builder()
                .userId(4L).productId(101L).storeId(1L).productName("린넨 오버핏 셔츠").thumbnailUrl("https://cdn/p.jpg")
                .writerDisplayName("곳간러버").content("재입고 언제 되나요?").secret(true)
                .build());
        inquiry.answer("다음 주 예정입니다", NOW);
        em.flush();
        em.clear();

        // when
        ProductInquiry found = inquiryRepository.findById(inquiry.getId()).orElseThrow();

        // then
        assertThat(found.isSecret()).isTrue();
        assertThat(found.isAnswered()).isTrue();
        assertThat(found.getAnswerContent()).isEqualTo("다음 주 예정입니다");
        assertThat(found.getProductName()).isEqualTo("린넨 오버핏 셔츠");
        assertThat(found.getThumbnailUrl()).isEqualTo("https://cdn/p.jpg");
        assertThat(found.getWriterDisplayName()).isEqualTo("곳간러버");
        assertThat(found.getCreatedAt()).isNotNull();
    }

    @Test
    @DisplayName("처리한 eventId 는 존재 확인되고, 같은 eventId 재저장은 uk 가 막는다")
    void processedEvent_existsAndRejectsDuplicate() {
        // given
        String eventId = "6f1c2a3b-0000-4000-8000-000000000001";
        processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, "OrderItemConfirmed"));

        // when · then
        assertThat(processedEventRepository.existsByEventId(eventId)).isTrue();
        assertThat(processedEventRepository.existsByEventId("other")).isFalse();
        assertThatThrownBy(() -> processedEventRepository.saveAndFlush(new ProcessedEvent(eventId, "OrderItemConfirmed")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
