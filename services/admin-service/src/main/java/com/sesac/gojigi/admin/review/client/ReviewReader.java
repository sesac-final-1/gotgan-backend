package com.sesac.gojigi.admin.review.client;

import tools.jackson.databind.JsonNode;

/**
 * review-service 리뷰 조회 · 블라인드 (10/26 확인 요청 4-2 계약) · 회원별 리뷰 (review.md 8-3) — 응답은 화면에 그대로 전달하므로 JsonNode.
 * 응답: {reviewId, productId, storeId, userId, rating, content, imageUrls[], blinded, blindReason, createdAt}
 */
public interface ReviewReader {

    /** 없는 · 삭제된 리뷰는 404 REVIEW_NOT_FOUND 를 그대로 전달 */
    JsonNode get(Long reviewId);

    /** 블라인드 · 해제 — 같은 상태 재요청은 200(멱등). blinded=true 면 reason 필수 */
    JsonNode blind(Long reviewId, boolean blinded, String reason);

    /** 회원 상세 "작성 리뷰" — 삭제 제외 · 블라인드 포함, 최신순 PageResponse. 없는 회원은 빈 목록 */
    JsonNode listByUser(Long userId, Integer page, Integer size);
}
