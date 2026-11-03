package com.sesac.gojigi.user.user.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * order 구매자 주문 통계 (내부) — GET /internal/stats/orders?buyerId= (order InternalOrderStatsController).
 * inProgressCount 는 진행 중 클레임이 걸린 상품주문도 포함하므로 user 는 탈퇴 검사에 inProgressCount 만 쓴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BuyerOrderStatsResponse(Long buyerId, long inProgressCount, long openClaimCount) {
}
