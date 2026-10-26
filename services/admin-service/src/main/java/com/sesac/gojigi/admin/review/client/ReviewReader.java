package com.sesac.gojigi.admin.review.client;

import tools.jackson.databind.JsonNode;

/**
 * review-service 리뷰 조회 · 블라인드 (10/26 확인 요청 4-2 계약) — 응답은 신고 화면에 그대로 전달하므로 JsonNode.
 * review 내부 API 배포 전에는 MockReviewClient(app.client.review-mock=true), 배포 뒤 ReviewClientAdapter 로 바뀐다.
 * 응답: {reviewId, productId, storeId, userId, rating, content, imageUrls[], blinded, blindReason, createdAt}
 */
public interface ReviewReader {

    /** 없는 · 삭제된 리뷰는 404 REVIEW_NOT_FOUND 를 그대로 전달 */
    JsonNode get(Long reviewId);

    /** 블라인드 · 해제 — 같은 상태 재요청은 200(멱등). blinded=true 면 reason 필수 */
    JsonNode blind(Long reviewId, boolean blinded, String reason);
}
