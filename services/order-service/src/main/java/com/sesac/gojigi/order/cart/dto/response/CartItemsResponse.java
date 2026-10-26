package com.sesac.gojigi.order.cart.dto.response;

import com.sesac.gojigi.order.cart.entity.CartItem;
import java.util.List;

/** 담기 · 삭제 뒤 내 장바구니 — 옵션 · 수량만 (가격 · 품절 · 스토어별 묶음은 조회 GET /api/carts, ORD-02) */
public record CartItemsResponse(List<Item> items, int cartCount) {

    public record Item(Long productOptionId, Long productId, int quantity, boolean checked) {
    }

    public static CartItemsResponse of(List<CartItem> cart) {
        return new CartItemsResponse(cart.stream()
                .map(c -> new Item(c.getProductOptionId(), c.getProductId(), c.getQuantity(), c.isChecked()))
                .toList(), cart.size());
    }
}
