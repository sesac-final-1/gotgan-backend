package com.sesac.gojigi.order.cart.dto.response;

import java.util.List;

/**
 * 장바구니 조회 (ORD-02 GET /api/carts) — 스토어별 묶음 · 스토어별 배송비 1회 · 무료배송까지 남은 금액. 가격 · 품절은 조회 때 product 에서 다시.
 * 금액 · 배송비는 선택(checked)되고 구매 가능한 줄만으로 계산한다 (주문서에 넘어가는 줄과 같다).
 * unavailable: product 에 없는 옵션(삭제 · 판매 종료) — 상품 정보 없이 옵션 ID · 수량만, 화면에서 삭제를 안내.
 */
public record CartResponse(List<StoreGroup> stores, List<UnavailableItem> unavailable, int cartCount, int checkedCount,
                           long totalProductAmt, long totalShippingFee, long totalAmt) {

    /**
     * 스토어 1곳 — productAmt: 선택된 구매 가능 상품 합계, shippingFee: 그 합계 기준 묶음 배송비(선택한 상품이 없으면 0),
     * amountToFree: 무료배송까지 더 담을 금액(무료이거나 선택 없음이면 0)
     */
    public record StoreGroup(Long storeId, String storeName, long productAmt, long shippingFee, long freeThreshold,
                             long amountToFree, List<Line> items) {
    }

    /** 옵션 1줄 — unitPrice = 판매가 + 옵션 추가금 (product 현재 값), lineAmt = unitPrice × quantity. purchasable 이 false 면 주문 불가(판매중지 등) */
    public record Line(Long productOptionId, Long productId, String productName, String optionName, String imageUrl,
                       long unitPrice, int quantity, boolean checked, boolean purchasable, int stock, long lineAmt) {
    }

    public record UnavailableItem(Long productOptionId, Long productId, int quantity) {
    }
}
