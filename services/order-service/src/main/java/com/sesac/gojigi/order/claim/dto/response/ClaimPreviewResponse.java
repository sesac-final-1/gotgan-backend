package com.sesac.gojigi.order.claim.dto.response;

import com.sesac.gojigi.order.claim.entity.ClaimReasonCode;
import com.sesac.gojigi.order.claim.entity.ClaimType;

/**
 * 클레임 신청 화면의 환불 예정액 (CLM · /mypage/claim/:lineId) — 신청할 수 있을 때만 응답한다(못 하면 신청과 같은 오류).
 * shippingFee: 취소는 돌려받는 배송비(+, 스토어 마지막 상품주문), 반품은 빼는 반품 배송비(-, 단순변심 등 구매자 사유면 스토어 기본 배송비 × 2), 교환은 0.
 * refundAmt = 카드 등으로 돌려받을 금액, restorePoint = 돌려받을 사용 포인트, reclaimPoint = 회수될 적립 포인트(실제 적립된 만큼까지).
 */
public record ClaimPreviewResponse(Long orderItemId, ClaimType type, ClaimReasonCode reasonCode, long itemAmt,
                                   long shippingFee, boolean sellerFault, long refundAmt, long restorePoint,
                                   long reclaimPoint) {
}
