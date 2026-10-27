package com.sesac.gojigi.order.claim.dto.response;

import com.sesac.gojigi.common.web.dto.PageResponse;
import com.sesac.gojigi.order.claim.dto.request.ClaimStage;
import com.sesac.gojigi.order.claim.entity.ClaimType;
import java.util.Map;

/**
 * 판매자 클레임 목록 (GET /api/seller/claims) — 서버 페이징(최신 요청 먼저).
 * stageCounts: 처리대기(OPEN) · 처리완료(DONE) 개수 — 고른 유형 기준. typeCounts: 유형별 개수 — 고른 단계 기준. 둘 다 모든 키(없으면 0).
 */
public record SellerClaimListResponse(Map<ClaimStage, Long> stageCounts, Map<ClaimType, Long> typeCounts,
                                      PageResponse<ClaimDetailResponse> items) {
}
