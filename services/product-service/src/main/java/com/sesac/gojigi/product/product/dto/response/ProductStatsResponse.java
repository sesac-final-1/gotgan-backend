package com.sesac.gojigi.product.product.dto.response;

import java.util.List;

/**
 * 상품 통계 (product.md 5절) — storeId 가 있으면 store, 없으면 platform 만 채우고 나머지는 null (settlement /internal/stats/settlements 와 같은 모양)
 */
public record ProductStatsResponse(Store store, Platform platform) {

    /** stoppedCount = STOPPED · BLOCKED 합계, lowStockProducts = 재고 적은 순 최대 10개 */
    public record Store(long onSaleCount, long lowStockCount, long soldOutCount, long stoppedCount, List<LowStock> lowStockProducts) {
    }

    public record LowStock(Long productId, String name, long stock, String imageUrl) {
    }

    public record Platform(long onSaleCount, long lowStockCount, long soldOutCount, long blockedCount) {
    }
}
