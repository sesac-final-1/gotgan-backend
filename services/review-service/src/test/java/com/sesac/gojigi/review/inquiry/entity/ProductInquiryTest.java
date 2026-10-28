package com.sesac.gojigi.review.inquiry.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ProductInquiryTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 28, 10, 0);

    private static ProductInquiry inquiry(boolean secret) {
        return ProductInquiry.builder()
                .userId(4L).productId(101L).storeId(1L).content("M이랑 L 중 어떤 걸 추천하시나요?").secret(secret)
                .build();
    }

    @Test
    @DisplayName("답변을 달면 답변됨이 되고, 다시 달면 덮어쓴다")
    void answer_overwritesSingleAnswer() {
        // given
        ProductInquiry inquiry = inquiry(false);
        assertThat(inquiry.isAnswered()).isFalse();

        // when
        inquiry.answer("M 추천드립니다", NOW);
        inquiry.answer("M 추천드립니다! 오버핏이에요", NOW.plusHours(1));

        // then
        assertThat(inquiry.isAnswered()).isTrue();
        assertThat(inquiry.getAnswerContent()).isEqualTo("M 추천드립니다! 오버핏이에요");
        assertThat(inquiry.getAnsweredAt()).isEqualTo(NOW.plusHours(1));
    }

    @Test
    @DisplayName("삭제하면 삭제 시각만 남는다 (물리 삭제 없음)")
    void softDelete_marksDeleted() {
        // given
        ProductInquiry inquiry = inquiry(false);

        // when
        inquiry.softDelete(NOW);

        // then
        assertThat(inquiry.isDeleted()).isTrue();
        assertThat(inquiry.getDeletedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("작성자 · 해당 스토어만 소유권 true")
    void ownership_trueOnlyForWriterAndStore() {
        ProductInquiry inquiry = inquiry(false);

        assertThat(inquiry.isOwnedBy(4L)).isTrue();
        assertThat(inquiry.isOwnedBy(5L)).isFalse();
        assertThat(inquiry.belongsToStore(1L)).isTrue();
        assertThat(inquiry.belongsToStore(2L)).isFalse();
    }

    @Test
    @DisplayName("공개글은 비로그인도 본문을 본다")
    void isReadableBy_publicInquiry_readableByAnyone() {
        assertThat(inquiry(false).isReadableBy(null, null)).isTrue();
    }

    @Test
    @DisplayName("비밀글은 작성자 · 해당 스토어 판매자만 본문을 본다")
    void isReadableBy_secretInquiry_onlyWriterAndStore() {
        ProductInquiry inquiry = inquiry(true);

        assertThat(inquiry.isReadableBy(4L, null)).isTrue();
        assertThat(inquiry.isReadableBy(9L, 1L)).isTrue();
        assertThat(inquiry.isReadableBy(5L, null)).isFalse();
        assertThat(inquiry.isReadableBy(9L, 2L)).isFalse();
        assertThat(inquiry.isReadableBy(null, null)).isFalse();
    }
}
