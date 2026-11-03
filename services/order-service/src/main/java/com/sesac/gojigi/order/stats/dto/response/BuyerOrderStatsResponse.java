package com.sesac.gojigi.order.stats.dto.response;

/**
 * 구매자 주문 통계 — user 회원 탈퇴 검사(O-19).
 * inProgressCount = 진행 중 상품주문 수 — 스토어 통계 inProgressCount 와 같은 기준(결제 완료 ~ 배송 완료 · 취소 · 반품 · 교환 요청
 * 이거나 진행 중 클레임이 걸린 상품주문), 한 건은 한 번만. openClaimCount = 진행 중 클레임(REQUESTED · APPROVED) 수.
 * 둘 중 하나라도 1 이상이면 user 가 탈퇴를 거절한다.
 */
public record BuyerOrderStatsResponse(Long buyerId, long inProgressCount, long openClaimCount) {
}
