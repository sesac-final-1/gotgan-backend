package com.sesac.gojigi.product.product.service;

import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.product.dto.response.ProductStatsResponse;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 상품 통계 (PRD-11 · product.md 5절) — 판매자 대시보드(store) · 관리자 대시보드(admin)가 병렬로 부른다.
 * 삭제 안 된 ON_SALE 상품만 옵션 재고 합계로 나눈다 (카테고리 · 스토어 상태는 보지 않음 — 판매자는 자기 상품 현황을 그대로 봐야 하므로).
 */
@Service
@RequiredArgsConstructor
public class ProductStatsService {

    static final int LOW_STOCK_THRESHOLD = 20;
    private static final int LOW_STOCK_LIMIT = 10;

    private final ProductRepository productRepository;
    private final ImageUrls imageUrls;

    @Transactional(readOnly = true)
    public ProductStatsResponse stats(Long storeId) {
        List<OnSale> onSale = productRepository.findOnSaleStocks(storeId).stream()
                .map(row -> new OnSale((Long) row[0], (String) row[1], ((Number) row[2]).longValue()))
                .toList();
        long inStock = onSale.stream().filter(p -> p.stock() > 0).count();
        long soldOut = onSale.size() - inStock;
        List<OnSale> lowStock = onSale.stream().filter(p -> p.stock() > 0 && p.stock() < LOW_STOCK_THRESHOLD).toList();

        if (storeId == null) {
            long blocked = productRepository.countByStatuses(null, List.of(ProductStatus.BLOCKED));
            return new ProductStatsResponse(null,
                    new ProductStatsResponse.Platform(inStock, lowStock.size(), soldOut, blocked));
        }
        long stopped = productRepository.countByStatuses(storeId, List.of(ProductStatus.STOPPED, ProductStatus.BLOCKED));
        return new ProductStatsResponse(
                new ProductStatsResponse.Store(inStock, lowStock.size(), soldOut, stopped, lowStockProducts(lowStock)), null);
    }

    /** 재고 적은 순(같으면 productId 순) 최대 10개 + 대표 이미지 */
    private List<ProductStatsResponse.LowStock> lowStockProducts(List<OnSale> lowStock) {
        List<OnSale> top = lowStock.stream()
                .sorted(Comparator.comparingLong(OnSale::stock).thenComparing(OnSale::productId))
                .limit(LOW_STOCK_LIMIT)
                .toList();
        if (top.isEmpty()) {
            return List.of();
        }
        Map<Long, String> imageKeys = productRepository.findMainImageKeys(top.stream().map(OnSale::productId).toList()).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (String) row[1]));
        return top.stream()
                .map(p -> new ProductStatsResponse.LowStock(p.productId(), p.name(), p.stock(), imageUrls.of(imageKeys.get(p.productId()))))
                .toList();
    }

    private record OnSale(Long productId, String name, long stock) {
    }
}
