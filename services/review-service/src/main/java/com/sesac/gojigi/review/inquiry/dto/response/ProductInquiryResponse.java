package com.sesac.gojigi.review.inquiry.dto.response;

import com.sesac.gojigi.review.inquiry.entity.ProductInquiry;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** 상품 상세 Q&A 한 건 (review.md 16절) — 프론트 ShopProductQna. 비밀글은 작성자 · 그 스토어 판매자가 아니면 내용 · 답변을 비운다 */
public record ProductInquiryResponse(
        @Schema(description = "상품 문의 ID", example = "12")
        Long inquiryId,
        @Schema(description = "작성자 공개 표시 이름 (닉네임, 없으면 가운데 가린 실명) — 비밀글도 보임", example = "곳간러버")
        String writerDisplayName,
        @Schema(description = "비밀글", example = "false")
        boolean secret,
        @Schema(description = "내가 쓴 문의 — 수정 · 삭제 버튼용 (비로그인은 false)", example = "false")
        boolean mine,
        @Schema(description = "문의 내용 — 볼 수 없는 비밀글이면 null", example = "M이랑 L 중 어떤 걸 추천하시나요?")
        String content,
        @Schema(description = "답변 완료 — 비밀글도 상태는 보임", example = "true")
        boolean answered,
        @Schema(description = "판매자 답변 — 없거나 볼 수 없는 비밀글이면 null", example = "M 추천드립니다.")
        String answerContent,
        @Schema(description = "답변 시각 — 없으면 null", example = "2026-10-29T11:00:00+09:00")
        OffsetDateTime answeredAt,
        @Schema(description = "등록 시각", example = "2026-10-29T10:00:00+09:00")
        OffsetDateTime createdAt) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** viewerUserId · viewerStoreId — 비로그인은 null */
    public static ProductInquiryResponse of(ProductInquiry inquiry, Long viewerUserId, Long viewerStoreId) {
        boolean readable = inquiry.isReadableBy(viewerUserId, viewerStoreId);
        return new ProductInquiryResponse(inquiry.getId(), inquiry.getWriterDisplayName(), inquiry.isSecret(),
                inquiry.isOwnedBy(viewerUserId), readable ? inquiry.getContent() : null, inquiry.isAnswered(),
                readable ? inquiry.getAnswerContent() : null, kst(inquiry.getAnsweredAt()), kst(inquiry.getCreatedAt()));
    }

    private static OffsetDateTime kst(LocalDateTime time) {
        return time == null ? null : time.atZone(KST).toOffsetDateTime();
    }
}
