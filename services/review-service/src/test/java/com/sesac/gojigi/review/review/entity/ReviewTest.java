package com.sesac.gojigi.review.review.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.sesac.gojigi.review.eligibility.entity.ReviewEligibility;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class ReviewTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);

    private static ReviewEligibility eligibility() {
        return ReviewEligibility.builder()
                .orderItemId(11L).userId(4L).productId(101L).storeId(1L)
                .buyerDisplayName("홍*동").productName("린넨 오버핏 셔츠").confirmedAt(NOW.minusDays(1))
                .build();
    }

    private static Review review() {
        return Review.write(eligibility(), 5, "핏이 예뻐요", "<p>오버핏이에요</p>", ReviewFit.TRUE_TO_SIZE);
    }

    @Nested
    @DisplayName("작성 · 수정")
    class WriteAndEdit {

        @Test
        @DisplayName("작성하면 상품주문 · 작성자 · 상품 · 스토어 · 표시 이름을 작성 자격에서 복사한다")
        void write_copiesSnapshotFromEligibility() {
            // given
            ReviewEligibility eligibility = eligibility();

            // when
            Review review = Review.write(eligibility, 4, null, "좋아요", null);

            // then
            assertThat(review.getEligibility()).isSameAs(eligibility);
            assertThat(review.getOrderItemId()).isEqualTo(11L);
            assertThat(review.getUserId()).isEqualTo(4L);
            assertThat(review.getProductId()).isEqualTo(101L);
            assertThat(review.getStoreId()).isEqualTo(1L);
            assertThat(review.getWriterDisplayName()).isEqualTo("홍*동");
            assertThat(review.getRating()).isEqualTo(4);
            assertThat(review.getContent()).isEqualTo("좋아요");
            assertThat(review.isBlinded()).isFalse();
            assertThat(review.hasReply()).isFalse();
            assertThat(review.isDeleted()).isFalse();
        }

        @Test
        @DisplayName("본인 수정은 별점 · 한줄 요약 · 본문 · 사이즈 체감을 바꾼다")
        void edit_changesFields() {
            // given
            Review review = review();

            // when
            review.edit(2, "길이가 길어요", "수선했어요", ReviewFit.LARGE);

            // then
            assertThat(review.getRating()).isEqualTo(2);
            assertThat(review.getTitle()).isEqualTo("길이가 길어요");
            assertThat(review.getContent()).isEqualTo("수선했어요");
            assertThat(review.getFit()).isEqualTo(ReviewFit.LARGE);
        }
    }

    @Nested
    @DisplayName("판매자 답글")
    class Reply {

        @Test
        @DisplayName("답글을 달면 내용과 시각이 저장되고, 다시 달면 덮어쓴다")
        void reply_overwritesSingleReply() {
            // given
            Review review = review();

            // when
            review.reply("감사합니다", NOW);
            review.reply("감사합니다! 세탁은 찬물 단독세탁 추천드려요", NOW.plusHours(1));

            // then
            assertThat(review.hasReply()).isTrue();
            assertThat(review.getReplyContent()).isEqualTo("감사합니다! 세탁은 찬물 단독세탁 추천드려요");
            assertThat(review.getRepliedAt()).isEqualTo(NOW.plusHours(1));
        }
    }

    @Nested
    @DisplayName("관리자 블라인드")
    class Blind {

        @Test
        @DisplayName("블라인드하면 사유 · 시각이 저장된다")
        void blind_setsReasonAndTime() {
            // given
            Review review = review();

            // when
            review.blind("욕설 · 비방", NOW);

            // then
            assertThat(review.isBlinded()).isTrue();
            assertThat(review.getBlindReason()).isEqualTo("욕설 · 비방");
            assertThat(review.getBlindedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("이미 블라인드면 다시 요청해도 처음 사유 · 시각을 그대로 둔다 (멱등)")
        void blind_whenAlreadyBlinded_keepsFirstReasonAndTime() {
            // given
            Review review = review();
            review.blind("욕설 · 비방", NOW);

            // when
            review.blind("광고", NOW.plusHours(1));

            // then
            assertThat(review.getBlindReason()).isEqualTo("욕설 · 비방");
            assertThat(review.getBlindedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("해제하면 사유 · 시각을 비운다, 블라인드가 아니어도 그대로 해제 상태")
        void unblind_clearsReasonAndTime() {
            // given
            Review review = review();
            review.blind("욕설 · 비방", NOW);

            // when
            review.unblind();
            review.unblind();

            // then
            assertThat(review.isBlinded()).isFalse();
            assertThat(review.getBlindReason()).isNull();
            assertThat(review.getBlindedAt()).isNull();
        }
    }

    @Nested
    @DisplayName("삭제 · 소유권")
    class DeleteAndOwnership {

        @Test
        @DisplayName("삭제하면 삭제 시각만 남는다 (물리 삭제 없음)")
        void softDelete_marksDeleted() {
            // given
            Review review = review();

            // when
            review.softDelete(NOW);

            // then
            assertThat(review.isDeleted()).isTrue();
            assertThat(review.getDeletedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("작성자 본인만 true — 다른 회원의 리뷰 수정 · 삭제 차단")
        void isOwnedBy_trueOnlyForWriter() {
            Review review = review();

            assertThat(review.isOwnedBy(4L)).isTrue();
            assertThat(review.isOwnedBy(5L)).isFalse();
        }

        @Test
        @DisplayName("해당 스토어만 true — 다른 스토어 리뷰에 답글 차단")
        void belongsToStore_trueOnlyForReviewStore() {
            Review review = review();

            assertThat(review.belongsToStore(1L)).isTrue();
            assertThat(review.belongsToStore(2L)).isFalse();
        }
    }
}
