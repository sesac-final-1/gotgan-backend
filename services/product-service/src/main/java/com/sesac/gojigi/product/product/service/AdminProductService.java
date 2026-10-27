package com.sesac.gojigi.product.product.service;

import com.sesac.gojigi.product.category.entity.Category;
import com.sesac.gojigi.product.category.repository.CategoryRepository;
import com.sesac.gojigi.product.image.service.ImageUrls;
import com.sesac.gojigi.product.product.dto.request.ProductBlockRequest;
import com.sesac.gojigi.product.product.dto.response.ProductBlockResponse;
import com.sesac.gojigi.product.product.dto.response.SellerProductResponse;
import com.sesac.gojigi.product.product.entity.Product;
import com.sesac.gojigi.product.product.entity.ProductStatus;
import com.sesac.gojigi.product.product.exception.ProductErrorCode;
import com.sesac.gojigi.product.product.exception.ProductException;
import com.sesac.gojigi.product.product.repository.ProductRepository;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 관리자 상품 조회 · 강제 판매중지 (PRD-13 · product.md 6절) — admin-service 스토어 상세 화면이 호출 */
@Service
@RequiredArgsConstructor
public class AdminProductService {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ImageUrls imageUrls;

    /** 스토어 상품 전체 (6-1절) — 판매자 상품 목록(11절)과 같은 응답, 삭제된 상품만 빠짐 */
    @Transactional(readOnly = true)
    public List<SellerProductResponse> listByStore(Long storeId) {
        List<Product> products = productRepository.findByStoreIdAndDeletedAtIsNullOrderByCreatedAtDescIdDesc(storeId);
        if (products.isEmpty()) {
            return List.of();
        }
        List<Long> ids = products.stream().map(Product::getId).toList();
        Map<Long, Long> stocks = toMap(productRepository.sumStockByProductIds(ids), row -> ((Number) row[1]).longValue());
        Map<Long, String> imageKeys = toMap(productRepository.findMainImageKeys(ids), row -> (String) row[1]);
        Map<Long, Category> categories = categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Function.identity()));
        return products.stream()
                .map(p -> toResponse(p, stocks.getOrDefault(p.getId(), 0L), imageKeys.get(p.getId()), categories))
                .toList();
    }

    /** 강제 판매중지 · 해제 (6-2절) — 해제하면 STOPPED(판매자가 점검 후 재개), BLOCKED 가 아니면 그대로 200 */
    @Transactional
    public ProductBlockResponse block(Long productId, ProductBlockRequest request) {
        Product product = productRepository.findByIdAndDeletedAtIsNull(productId)
                .orElseThrow(() -> new ProductException(ProductErrorCode.PRODUCT_NOT_FOUND));
        if (request.blocked()) {
            if (request.reason() == null || request.reason().isBlank()) {
                throw new ProductException(ProductErrorCode.PRODUCT_BLOCK_REASON_REQUIRED);
            }
            product.block(request.reason().strip());
        } else {
            product.unblock();
        }
        return ProductBlockResponse.from(product);
    }

    private SellerProductResponse toResponse(Product p, long totalStock, String imageKey, Map<Long, Category> categories) {
        Category category = categories.get(p.getCategoryId());
        Category top = category == null || category.isTop() ? category : categories.get(category.getParentId());
        String hiddenReason = hiddenReason(p, category, top);
        return new SellerProductResponse(p.getId(), p.getName(), p.getCategoryId(),
                category == null ? null : category.getName(), top == null ? null : top.getName(),
                p.getPrice(), p.getDiscountRate(), p.getSalePrice(), imageUrls.of(imageKey), totalStock, totalStock == 0,
                p.getStatus(), p.getStatusReason(), hiddenReason == null, hiddenReason,
                p.getCreatedAt() == null ? null : p.getCreatedAt().atZone(KST).toOffsetDateTime());
    }

    /** 쇼핑몰에 안 보이는 이유 — 상태 → 카테고리(하위 · 상위) → 스토어 순으로 먼저 걸린 것. 보이면 null */
    private static String hiddenReason(Product p, Category category, Category top) {
        if (p.getStatus() != ProductStatus.ON_SALE) {
            return p.getStatus().name();
        }
        if (category == null || !category.isActive() || top == null || !top.isActive()) {
            return "CATEGORY_INACTIVE";
        }
        return p.isStoreActive() ? null : "STORE_INACTIVE";
    }

    private static <V> Map<Long, V> toMap(List<Object[]> rows, Function<Object[], V> value) {
        return rows.stream().collect(Collectors.toMap(row -> (Long) row[0], value));
    }
}
